/*
 * Copyright 2008-2009 by Emeric Vernat, Bull
 *
 *     This file is part of Java Melody.
 *
 * Java Melody is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Java Melody is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Java Melody.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.bull.javamelody;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;

/**
 * Servlet de collecte utilisée uniquement pour serveur de collecte séparé de l'application monitorée.
 * @author Emeric Vernat
 */
public class CollectorServlet extends HttpServlet {
	private static final String BACK_LINK = "<a href='javascript:history.back()'><img src='?resource=action_back.png' alt='#Retour#'/> #Retour#</a>&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;";

	private static final String COOKIE_NAME = "monitoring";

	private static final long serialVersionUID = -2070469677921953224L;

	@SuppressWarnings("all")
	private static final Logger LOGGER = Logger.getLogger("monitoring");

	private Pattern allowedAddrPattern;

	@SuppressWarnings("all")
	private transient CollectorServer collectorServer;

	/** {@inheritDoc} */
	@Override
	public void init(ServletConfig config) throws ServletException {
		super.init(config);
		Parameters.initialize(config.getServletContext());
		if (!Boolean.parseBoolean(Parameters.getParameter(Parameter.LOG))) {
			// si log désactivé dans serveur de collecte,
			// alors pas de log, comme dans webapp
			LOGGER.setLevel(Level.WARN);
		}
		// dans le serveur de collecte, on est sûr que log4j est disponible
		LOGGER.info("initialisation de la servlet de collecte du monitoring");
		if (Parameters.getParameter(Parameter.ALLOWED_ADDR_PATTERN) != null) {
			allowedAddrPattern = Pattern.compile(Parameters
					.getParameter(Parameter.ALLOWED_ADDR_PATTERN));
		}

		try {
			collectorServer = new CollectorServer();
		} catch (final IOException e) {
			throw new ServletException(e.getMessage(), e);
		}
	}

	/** {@inheritDoc} */
	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException,
			IOException {
		final long start = System.currentTimeMillis();
		if (isAddressAllowed(req)) {
			resp.sendError(HttpServletResponse.SC_FORBIDDEN, "Accès interdit");
			return;
		}
		final String application = getApplication(req, resp);
		I18N.bindLocale(req.getLocale());
		try {
			if (application == null) {
				writeOnlyAddApplication(resp);
				return;
			}
			if (!collectorServer.isApplicationDataAvailable(application)) {
				resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
						"Données non disponibles pour l'application " + application);
				return;
			}
			doMonitoring(req, resp, application);
		} finally {
			I18N.unbindLocale();
			if (LOGGER.isDebugEnabled()) {
				LOGGER.debug("monitoring depuis " + req.getRemoteAddr() + ", request="
						+ req.getRequestURI()
						+ (req.getQueryString() != null ? '?' + req.getQueryString() : "")
						+ ", application=" + application + " en "
						+ (System.currentTimeMillis() - start) + "ms");
			}
		}
	}

	/** {@inheritDoc} */
	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp)
			throws ServletException, IOException {
		if (isAddressAllowed(req)) {
			resp.sendError(HttpServletResponse.SC_FORBIDDEN, "Accès interdit");
			return;
		}
		I18N.bindLocale(req.getLocale());
		try {
			// post du formulaire d'ajout d'application à monitorer
			final String appName = req.getParameter("appName");
			final String appUrls = req.getParameter("appUrls");
			if (appName == null || appUrls == null) {
				writeMessage(req, resp, getApplication(req, resp), I18N
						.getString("donnees_manquantes"));
				return;
			}
			if (!appUrls.startsWith("http://") && !appUrls.startsWith("https://")) {
				writeMessage(req, resp, getApplication(req, resp), I18N.getString("urls_format"));
				return;
			}
			final List<URL> urls = Parameters.parseUrl(appUrls);
			collectorServer.addCollectorApplication(appName, urls);
			LOGGER.info("ajout application monitorée : " + appName);
			LOGGER.info("urls de l'application monitorée : " + urls);
			showAlertAndRedirectTo(resp, I18N.getFormattedString("application_ajoutee", appName),
					"?application=" + appName);
		} catch (final FileNotFoundException e) {
			final String message = I18N.getString("monitoring_configure");
			LOGGER.warn(message, e);
			writeMessage(req, resp, getApplication(req, resp), message + '\n' + e.toString());
		} catch (final Exception e) {
			LOGGER.warn(e.toString(), e);
			writeMessage(req, resp, getApplication(req, resp), e.toString());
		} finally {
			I18N.unbindLocale();
		}
	}

	private void doMonitoring(HttpServletRequest req, HttpServletResponse resp, String application)
			throws IOException {
		try {
			final Collector collector = getCollectorByApplication(application);
			final MonitoringController monitoringController = new MonitoringController(collector,
					true);
			final String actionParameter = req.getParameter(MonitoringController.ACTION_PARAMETER);
			if ("remove_application".equalsIgnoreCase(actionParameter)) {
				collectorServer.removeCollectorApplication(application);
				final String message = I18N.getFormattedString("application_enlevee", application);
				showAlertAndRedirectTo(resp, message, "?");
				return;
			} else if (actionParameter != null
					&& Action.valueOfIgnoreCase(actionParameter) != Action.CLEAR_COUNTER) {
				// on forwarde l'action (gc, invalidate session(s) ou heap dump) sur l'application monitorée
				// et on récupère les informations à jour (notamment mémoire et nb de sessions)
				forwardActionAndUpdateData(req, application);
			} else {
				// nécessaire si action clear_counter
				monitoringController.executeActionIfNeeded(req);
			}
			final String partParameter = req.getParameter(MonitoringController.PART_PARAMETER);
			if (partParameter == null) {
				// la récupération de javaInformationsList doit être après forwardActionAndUpdateData
				// pour être à jour
				final List<JavaInformations> javaInformationsList = getJavaInformationsByApplication(application);
				monitoringController.doReport(req, resp, javaInformationsList);
			} else {
				doPart(req, resp, application, monitoringController, partParameter);
			}
		} catch (final RuntimeException e) {
			// catch RuntimeException pour éviter warning exception
			writeMessage(req, resp, application, e.getMessage());
		} catch (final Exception e) {
			writeMessage(req, resp, application, e.getMessage());
		}
	}

	private void doPart(HttpServletRequest req, HttpServletResponse resp, String application,
			MonitoringController monitoringController, String partParameter) throws IOException,
			ClassNotFoundException {
		if (MonitoringController.WEB_XML_PART.equalsIgnoreCase(partParameter)) {
			MonitoringController.noCache(resp);
			doProxy(req, resp, application, "part=web.xml");
		} else if (MonitoringController.POM_XML_PART.equalsIgnoreCase(partParameter)) {
			MonitoringController.noCache(resp);
			doProxy(req, resp, application, "part=pom.xml");
		} else if (MonitoringController.SESSIONS_PART.equalsIgnoreCase(partParameter)) {
			doSessions(req, resp, application, monitoringController);
		} else if (MonitoringController.CURRENT_REQUESTS_PART.equalsIgnoreCase(partParameter)) {
			doCurrentRequests(req, resp, application);
		} else if (MonitoringController.HEAP_HISTO_PART.equalsIgnoreCase(partParameter)) {
			doHeapHisto(req, resp, application, monitoringController);
		} else if (MonitoringController.PROCESSES_PART.equalsIgnoreCase(partParameter)) {
			doProcesses(req, resp, application);
		} else {
			final List<JavaInformations> javaInformationsList = getJavaInformationsByApplication(application);
			monitoringController.doReport(req, resp, javaInformationsList);
		}
	}

	private void doProxy(HttpServletRequest req, HttpServletResponse resp, String application,
			String urlParameter) throws IOException {
		// récupération à la demande du contenu du web.xml de la webapp monitorée
		// (et non celui du serveur de collecte),
		// on prend la 1ère url puisque le contenu de web.xml est censé être le même
		// dans tout l'éventuel cluster
		final URL url = getUrlsByApplication(application).get(0);
		// on récupère le contenu du web.xml sur la webapp et on transfert ce contenu
		final URL webXmlUrl = new URL(url.toString() + '&' + urlParameter);
		new LabradorRetriever(webXmlUrl).copyTo(req, resp);
	}

	private void doHeapHisto(HttpServletRequest req, HttpServletResponse resp, String application,
			MonitoringController monitoringController) throws IOException, ClassNotFoundException {
		// récupération à la demande des HeapHistogram
		HeapHistogram heapHistoTotal = null;
		for (final URL url : getUrlsByApplication(application)) {
			final URL heapHistoUrl = new URL(url.toString() + "&part=heaphisto");
			final LabradorRetriever labradorRetriever = new LabradorRetriever(heapHistoUrl);
			final HeapHistogram heapHisto = labradorRetriever.call();
			if (heapHistoTotal == null) {
				heapHistoTotal = heapHisto;
			} else {
				heapHistoTotal.add(heapHisto);
			}
		}
		monitoringController.setHeapHistogramIfCollectServer(heapHistoTotal);
		final List<JavaInformations> javaInformationsList = getJavaInformationsByApplication(application);
		monitoringController.doReport(req, resp, javaInformationsList);
	}

	private void doSessions(HttpServletRequest req, HttpServletResponse resp, String application,
			MonitoringController monitoringController) throws IOException, ClassNotFoundException {
		final String sessionId = req.getParameter(MonitoringController.SESSION_ID_PARAMETER);
		if (sessionId == null) {
			// récupération à la demande des sessions
			final List<SessionInformations> sessionsInformations = new ArrayList<SessionInformations>();
			for (final URL url : getUrlsByApplication(application)) {
				final URL sessionsUrl = new URL(url.toString() + "&part=sessions");
				final LabradorRetriever labradorRetriever = new LabradorRetriever(sessionsUrl);
				final List<SessionInformations> sessions = labradorRetriever.call();
				sessionsInformations.addAll(sessions);
			}
			SessionListener.sortSessions(sessionsInformations);
			monitoringController.setSessionsInformations(sessionsInformations);
			final List<JavaInformations> javaInformationsList = getJavaInformationsByApplication(application);
			monitoringController.doReport(req, resp, javaInformationsList);
		} else {
			SessionInformations found = null;
			for (final URL url : getUrlsByApplication(application)) {
				final URL sessionsUrl = new URL(url.toString() + "&part=sessions&sessionId="
						+ sessionId);
				final LabradorRetriever labradorRetriever = new LabradorRetriever(sessionsUrl);
				final SessionInformations session = (SessionInformations) labradorRetriever.call();
				if (session != null) {
					found = session;
					break;
				}
			}
			// si found est toujours null, alors la session a été invalidée
			monitoringController.setSessionsInformations(Collections.singletonList(found));
			final List<JavaInformations> javaInformationsList = getJavaInformationsByApplication(application);
			monitoringController.doReport(req, resp, javaInformationsList);
		}
	}

	private void doCurrentRequests(HttpServletRequest req, HttpServletResponse resp,
			String application) throws IOException {
		final PrintWriter writer = createWriterFromOutputStream(resp);
		final HtmlReport htmlReport = createHtmlReport(req, writer, application);
		htmlReport.writeHtmlHeader(false);
		writer.write("<div class='noPrint'>");
		I18N.writelnTo(BACK_LINK, writer);
		writer.write("<a href='?part=currentRequests&amp;period=");
		writer.write(MonitoringController.getPeriod(req).getCode());
		writer.write("'>");
		I18N.writelnTo("<img src='?resource=action_refresh.png' alt='#Actualiser#'/> #Actualiser#",
				writer);
		writer.write("</a></div>");
		for (final URL url : getUrlsByApplication(application)) {
			final String title = I18N.getString("Requetes_en_cours");
			final String htmlTitle = "<h3><img width='24' height='24' src='?resource=hourglass.png' alt='"
					+ title + "'/>" + title + " (" + getHostAndPort(url) + ")</h3>";
			writer.write(htmlTitle);
			writer.flush(); // flush du buffer de writer, sinon le copyTo passera avant dans l'outputStream
			final URL currentRequestsUrl = new URL(url.toString().replace(
					TransportFormat.SERIALIZED.getCode(), "html").replace(
					TransportFormat.XML.getCode(), "html")
					+ "&part=currentRequests");
			new LabradorRetriever(currentRequestsUrl).copyTo(req, resp);
		}
		htmlReport.writeHtmlFooter();
		writer.close();
	}

	private void doProcesses(HttpServletRequest req, HttpServletResponse resp, String application)
			throws IOException, ClassNotFoundException {
		final PrintWriter writer = createWriterFromOutputStream(resp);
		final HtmlReport htmlReport = createHtmlReport(req, writer, application);
		htmlReport.writeHtmlHeader(false);
		writer.write("<div class='noPrint'>");
		I18N.writelnTo(BACK_LINK, writer);
		writer.write("<a href='?part=processes'>");
		I18N.writelnTo("<img src='?resource=action_refresh.png' alt='#Actualiser#'/> #Actualiser#",
				writer);
		writer.write("</a></div>");
		for (final URL url : getUrlsByApplication(application)) {
			final String title = I18N.getString("Processus");
			final String htmlTitle = "<h3><img width='24' height='24' src='?resource=threads.png' alt='"
					+ title + "'/>&nbsp;" + title + " (" + getHostAndPort(url) + ")</h3>";
			writer.write(htmlTitle);
			writer.flush(); // flush du buffer de writer, sinon le copyTo passera avant dans l'outputStream
			final URL processesUrl = new URL(url.toString() + "&part=processes");
			final List<ProcessInformations> processes = new LabradorRetriever(processesUrl).call();
			new HtmlProcessInformationsReport(processes, writer).writeTable();
		}
		htmlReport.writeHtmlFooter();
		writer.close();
	}

	private HtmlReport createHtmlReport(HttpServletRequest req, PrintWriter writer,
			String application) {
		final Period period = MonitoringController.getPeriod(req);
		final Collector collector = getCollectorByApplication(application);
		final List<JavaInformations> javaInformationsList = getJavaInformationsByApplication(application);
		return new HtmlReport(collector, true, javaInformationsList, period, writer);
	}

	private static String getHostAndPort(URL url) {
		if (url.getPort() != -1) {
			return url.getHost() + ':' + url.getPort();
		}
		// port est -1 si c'est le port par défaut (80)
		return url.getHost();
	}

	private void writeMessage(HttpServletRequest req, HttpServletResponse resp, String application,
			String message) throws IOException {
		MonitoringController.noCache(resp);
		final Collector collector = getCollectorByApplication(application);
		final List<JavaInformations> javaInformationsList = getJavaInformationsByApplication(application);
		PrintWriter writer;
		try {
			writer = resp.getWriter();
		} catch (final Exception e) {
			writer = createWriterFromOutputStream(resp);
		}

		if (application == null) {
			showAlertAndRedirectTo(resp, message, "?");
		} else {
			final Period period = MonitoringController.getPeriod(req);
			new HtmlReport(collector, true, javaInformationsList, period, writer)
					.writeMessageIfNotNull(message, null);
		}
		writer.close();
	}

	private Collector getCollectorByApplication(String application) {
		return collectorServer.getCollectorByApplication(application);
	}

	private List<JavaInformations> getJavaInformationsByApplication(String application) {
		return collectorServer.getJavaInformationsByApplication(application);
	}

	static PrintWriter createWriterFromOutputStream(HttpServletResponse httpResponse)
			throws IOException {
		MonitoringController.noCache(httpResponse);
		httpResponse.setContentType(MonitoringController.HTML_CONTENT_TYPE);
		return new PrintWriter(new OutputStreamWriter(httpResponse.getOutputStream(),
				MonitoringController.HTML_CHARSET));
	}

	private static void writeOnlyAddApplication(HttpServletResponse resp) throws IOException {
		MonitoringController.noCache(resp);
		resp.setContentType(MonitoringController.HTML_CONTENT_TYPE);
		final PrintWriter writer = resp.getWriter();
		writer.write("<html><head><title>Monitoring</title></head><body>");
		HtmlReport.writeAddAndRemoveApplicationLinks(null, Period.JOUR, writer);
		writer.write("</body></html>");
	}

	private static void showAlertAndRedirectTo(HttpServletResponse resp, String message,
			String redirectTo) throws IOException {
		resp.setContentType(MonitoringController.HTML_CONTENT_TYPE);
		final PrintWriter writer = resp.getWriter();
		writer.write("<script type='text/javascript'>alert('");
		writer.write(I18N.javascriptEncode(message));
		writer.write("');location.href='");
		writer.write(redirectTo);
		writer.write("';</script>");
	}

	private boolean isAddressAllowed(HttpServletRequest req) {
		return allowedAddrPattern != null
				&& !allowedAddrPattern.matcher(req.getRemoteAddr()).matches();
	}

	private void forwardActionAndUpdateData(HttpServletRequest req, String application)
			throws IOException {
		final String actionParameter = req.getParameter(MonitoringController.ACTION_PARAMETER);
		final String sessionIdParameter = req
				.getParameter(MonitoringController.SESSION_ID_PARAMETER);
		final List<URL> urls = getUrlsByApplication(application);
		final List<URL> actionUrls = new ArrayList<URL>(urls.size());
		for (final URL url : urls) {
			final String tmp = url.toString() + "&action=" + actionParameter;
			final String actionUrl;
			if (sessionIdParameter == null) {
				actionUrl = tmp;
			} else {
				actionUrl = tmp + "&sessionId=" + sessionIdParameter;
			}
			actionUrls.add(new URL(actionUrl));
		}
		try {
			collectorServer.collectForApplication(application, actionUrls);
		} catch (final ClassNotFoundException e) {
			throw new IllegalStateException(e);
		}
	}

	private String getApplication(HttpServletRequest req, HttpServletResponse resp) {
		// on utilise un cookie client pour stocker l'application
		// car la page html est faite pour une seule application sans passer son nom en paramètre des requêtes
		// et pour ne pas perdre l'application choisie entre les reconnexions
		String application = req.getParameter("application");
		if (application == null) {
			// pas de paramètre application dans la requête, on cherche le cookie
			final Cookie[] cookies = req.getCookies();
			if (cookies != null) {
				for (final Cookie cookie : Arrays.asList(cookies)) {
					if (COOKIE_NAME.equals(cookie.getName())) {
						application = cookie.getValue();
						if (!collectorServer.isApplicationDataAvailable(application)) {
							cookie.setMaxAge(-1);
							resp.addCookie(cookie);
							application = null;
						}
						break;
					}
				}
			}
			if (application == null) {
				// pas de cookie, on prend la première application si elle existe
				application = collectorServer.getFirstApplication();
			}
		} else if (collectorServer.isApplicationDataAvailable(application)) {
			// un paramètre application est présent dans la requête: l'utilisateur a choisi une application,
			// donc on fixe le cookie
			final Cookie cookie = new Cookie(COOKIE_NAME, String.valueOf(application));
			cookie.setMaxAge(30 * 24 * 60 * 60); // cookie persistant, valide pendant 30 jours
			resp.addCookie(cookie);
		}
		return application;
	}

	private static List<URL> getUrlsByApplication(String application) throws IOException {
		assert application != null;
		return Parameters.getCollectorUrlsByApplications().get(application);
	}

	/** {@inheritDoc} */
	@Override
	public void destroy() {
		LOGGER.info("servlet de collecte en phase d'arrêt");
		collectorServer.stop();
		LOGGER.info("servlet de collecte arrêtée");
		super.destroy();
	}
}
