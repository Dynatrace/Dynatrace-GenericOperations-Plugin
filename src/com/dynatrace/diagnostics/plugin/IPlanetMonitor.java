
 /**
  * This template file was generated by dynaTrace client.
  * The dynaTrace community portal can be found here: http://community.compuwareapm.com/
  * For information how to publish a plugin please visit http://community.compuwareapm.com/plugins/contribute/
  **/ 

package com.dynatrace.diagnostics.plugin;

import com.dynatrace.diagnostics.pdk.*;
import com.sun.org.apache.xml.internal.security.utils.Base64;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.UsernamePasswordCredentials;
import org.apache.commons.httpclient.auth.AuthScope;
import org.apache.commons.httpclient.auth.CredentialsProvider;
import org.apache.commons.httpclient.methods.GetMethod;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.net.UnknownHostException;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.*;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.Document;

public class IPlanetMonitor implements Monitor {

	private static final Logger log = Logger.getLogger(IPlanetMonitor.class.getName());
	
	//initialize config variables
	private String username;
	private String password;
	private String authString;
	private String authStringEnc;
	private String minuendMeasure;
	private String subtrahendMeasure;
	
	private URL btDashboardUrl;
	private URL url;
	private URLConnection connection;
	
	/**
	 * The RangeGroup that will calculate the different second range groups 
	 * Set range section < 1 seconds && >1s & <2s && >2s & <3s & >3s & <4s  & >4s & <5s & >5s & <10s && >10s & <15s & > 15s		
	 */
	private RangeGroup rangeGroup;
	
	/**
	 * 
	 */
	private ServerRestAPI restAPI;
	
	
	HostnameVerifier defaultVerifier = HttpsURLConnection.getDefaultHostnameVerifier();
	XPath xpath = XPathFactory.newInstance().newXPath();
	private Document doc;
	
	/**
	 * Initializes the Plugin. This method is called in the following cases:
	 * <ul>
	 * <li>before <tt>execute</tt> is called the first time for this
	 * scheduled Plugin</li>
	 * <li>before the next <tt>execute</tt> if <tt>teardown</tt> was called
	 * after the last execution</li>
	 * </ul>
	 * <p>
	 * If the returned status is <tt>null</tt> or the status code is a
	 * non-success code then {@link Plugin#teardown() teardown()} will be called
	 * next.
	 * <p>
	 * Resources like sockets or files can be opened in this method.
	 * @param env
	 *            the configured <tt>MonitorEnvironment</tt> for this Plugin;
	 *            contains subscribed measures, but <b>measurements will be
	 *            discarded</b>
	 * @see Plugin#teardown()
	 * @return a <tt>Status</tt> object that describes the result of the
	 *         method call
	 */

	@Override
	public Status setup(MonitorEnvironment env) throws Exception {
		Status status = new Status(Status.StatusCode.Success);
		//check plugin environment configuration parameter values
		if (env == null || env.getHost() == null) {
			status.setStatusCode(Status.StatusCode.ErrorInternalConfigurationProblem);
			status.setShortMessage("Environment was not properly initialized. env.host must not be null.");
			status.setMessage("Environment was not properly initialized. env.host must not be null.");
			Exception e = new IllegalArgumentException("Environment was not properly initialized. env.host must not be null.");
			status.setException(e);
			log.log(Level.SEVERE, status.getMessage(), e);
			return status;
		}
		restAPI = new ServerRestAPI(env.getConfigString("dtServer"), env.getConfigString("username"), env.getConfigPassword("password"));
		rangeGroup = new RangeGroup();
		
		minuendMeasure = env.getConfigString("minuendMeasure");
		subtrahendMeasure = env.getConfigString("subtrahendMeasure");
		
		
		
		Collection<MonitorMeasure> measures = env.getMonitorMeasures("RangeGroup", "Range");		
		for (MonitorMeasure measure : measures){
			measure.setValue(0);
		}
		return status;
	}

	@Override
	public Status execute(MonitorEnvironment env) throws Exception {
		Status status = new Status();				
		logFine("Begin Plugin Execution");
		
		//Reset all ranges to 0
		rangeGroup.resetGroup();
		
		
		StringBuilder messageBuffer = new StringBuilder("URL: ");
		messageBuffer.append(url).append("\r\n");
		
		try {
			
			TrustSSL.trustAllCerts();	
			
			try {
				logInfo("Here");
				
				doc = restAPI.getDashboard(env.getConfigString("dashboardName"));
				NodeList listDataRows = doc.getElementsByTagName("chartdashlet");
				
				Element purePathLessWaitTime = (Element) listDataRows.item(0);
				Element countPurePaths = (Element)listDataRows.item(1);
				
				NodeList tmp = purePathLessWaitTime.getElementsByTagName("measure");
				logInfo("Length is: " + tmp.getLength());
				logInfo("Length of child is : " + tmp.item(0).getChildNodes().getLength());
				NodeList clientWaitTime = null;
				NodeList purepathTime = null;
				NodeList countPurepath;
				List<Double> purePathMinusWaitTimeDivCount = new ArrayList<Double>();
				
				for (int i = 0; i < tmp.getLength(); i++) {
					String measureName = tmp.item(i).getAttributes().getNamedItem("measure").getNodeValue();
					Element e = (Element)tmp.item(i);
					if (measureName.contains(subtrahendMeasure)) {
						clientWaitTime = e.getElementsByTagName("measurement");
					} else if (measureName.contains(minuendMeasure)){
						purepathTime = e.getElementsByTagName("measurement");
					}
				}
				
				for (int i = 0, j = 0; i < clientWaitTime.getLength() && j < purepathTime.getLength();) {
					Long clientWaitTimeStamp = Long.parseLong(clientWaitTime.item(i).getAttributes().getNamedItem("timestamp").getNodeValue());
					Long purePathTimeStamp = Long.parseLong(purepathTime.item(j).getAttributes().getNamedItem("timestamp").getNodeValue());
					Double sumClientWaitTime = Double.parseDouble(clientWaitTime.item(i).getAttributes().getNamedItem("sum").getNodeValue());
					Double sumPurePathTime = Double.parseDouble(purepathTime.item(j).getAttributes().getNamedItem("sum").getNodeValue());
					Double countPurePath = Double.parseDouble(purepathTime.item(j).getAttributes().getNamedItem("count").getNodeValue());
					
					logWarn("CLIENT TIME STAMP = " + clientWaitTimeStamp);
					logWarn("PUREPATH TIME STAMP " + purePathTimeStamp);
					double result = 0;
					if (purePathTimeStamp.longValue() < clientWaitTimeStamp.longValue()) {
						result = sumPurePathTime.doubleValue() / countPurePath.doubleValue();
						purePathMinusWaitTimeDivCount.add(result);
						j++;
						
					} else if(purePathTimeStamp.longValue() == clientWaitTimeStamp.longValue()){
						result = (sumPurePathTime - sumClientWaitTime) / countPurePath.doubleValue();
						purePathMinusWaitTimeDivCount.add(result);
						i++;
						j++;
					}
					
					//Get range section < 1seconds && >1s & <2s && >2s & <3s & >3s & <4s  & >4s & <5s & >5s & <10s && >10s & <15s & > 15s
					rangeGroup.calculateGroup(result);
				}
				
				logInfo("+++++++++++++HERE+++++++++++");
				
				
				
				Collection<MonitorMeasure> monitorMeasures = env.getMonitorMeasures("NettoGroup", "netDiff");
				for (MonitorMeasure measure : monitorMeasures) {
					measure.setValue(purePathMinusWaitTimeDivCount.get(purePathMinusWaitTimeDivCount.size() - 1));
				}
				
				//Set range section < 1 seconds && >1s & <2s && >2s & <3s & >3s & <4s  & >4s & <5s & >5s & <10s && >10s & <15s & > 15s
				//Set value of less than 1 second
				Collection<MonitorMeasure> lessThanOneCollection = env.getMonitorMeasures("RangeGroup", "Less_Than_One_Second");
				for(MonitorMeasure measure : lessThanOneCollection) {
					measure.setValue(rangeGroup.getNumberLessOneSecond());
				}
				//Set value of >1s & <2s	
				Collection<MonitorMeasure> betweenOneAndTwoCollection = env.getMonitorMeasures("RangeGroup", "Between_One_And_Two_Seconds");
				for(MonitorMeasure measure : betweenOneAndTwoCollection) {
					measure.setValue(rangeGroup.getBetweenOneTwoSecond());
				}
				//Set value of >2s & <3s
				Collection<MonitorMeasure> betweenTwoAndThreeCollection = env.getMonitorMeasures("RangeGroup", "Between_Two_And_Three_Seconds");
				for(MonitorMeasure measure : betweenTwoAndThreeCollection) {
					measure.setValue(rangeGroup.getBetweenTwoThreeSecond());
				}
				//Set value
				Collection<MonitorMeasure> betweenThreeAndFourCollection = env.getMonitorMeasures("RangeGroup", "Between_Three_And_Four_Seconds");
				for(MonitorMeasure measure : betweenThreeAndFourCollection) {
					measure.setValue(rangeGroup.getBetweenThreeFourSecond());
				}
				Collection<MonitorMeasure> betweenFourAndFiveSecondsCollection = env.getMonitorMeasures("RangeGroup", "Between_Four_And_Five_Seconds");
				for(MonitorMeasure measure : betweenFourAndFiveSecondsCollection) {
					measure.setValue(rangeGroup.getBetweenFourFiveSecond());
				}
				Collection<MonitorMeasure> betweenFiveAndTenSecondsCollection = env.getMonitorMeasures("RangeGroup", "Between_Five_And_Ten_Seconds");
				for(MonitorMeasure measure : betweenFiveAndTenSecondsCollection) {
					measure.setValue(rangeGroup.getBetweenFiveTenSecond());
				}
				Collection<MonitorMeasure> betweenTenAndFifteenSecondsCollection = env.getMonitorMeasures("RangeGroup", "Between_Ten_And_Fifteen_Seconds");
				for(MonitorMeasure measure : betweenTenAndFifteenSecondsCollection) {
					measure.setValue(rangeGroup.getBetweenTenFifteenSecond());
				}
				Collection<MonitorMeasure> moreThanFifteenCollection = env.getMonitorMeasures("RangeGroup", "More_Than_15_Seconds");
				for(MonitorMeasure measure : moreThanFifteenCollection) {
					measure.setValue(rangeGroup.getMoreFifteenSecond());
				}
				
				
				
			} catch(Throwable e) {
				e.printStackTrace();
				return status;
			}
			HttpsURLConnection.setDefaultHostnameVerifier(defaultVerifier);			
			logInfo("Retrieving XML Results...");
		} catch (ConnectException ce) {
			status.setException(ce);
			status.setStatusCode(Status.StatusCode.PartialSuccess);
			status.setShortMessage(ce == null ? "" : ce.getClass().getSimpleName());
			messageBuffer.append(ce == null ? "" : ce.getMessage());
			log.log(Level.SEVERE, status.getMessage(), ce);
		} catch (IOException ioe) {
			status.setException(ioe);
			status.setStatusCode(Status.StatusCode.ErrorTargetServiceExecutionFailed);
			status.setShortMessage(ioe == null ? "" : ioe.getClass().getSimpleName());
			messageBuffer.append(ioe == null ? "" : ioe.getMessage());
			//if (log.isLoggable(Level.SEVERE))
				log.severe("Requesting URL " + url.toString() /**httpRequest.getURI()**/ + " caused exception: " + ioe);
		} 	
		// calculate and set the measurements
		status.setMessage(messageBuffer.toString());
			logFine("Plugin Status: " + messageBuffer.toString());
		
		return status;
	}


	/**
	 * Shuts the Plugin down and frees resources. This method is called in the
	 * following cases:
	 * <ul>
	 * <li>the <tt>setup</tt> method failed</li>
	 * <li>the Plugin configuration has changed</li>
	 * <li>the execution duration of the Plugin exceeded the schedule timeout</li>
	 * <li>the schedule associated with this Plugin was removed</li>
	 * </ul>
	 *
	 * <p>
	 * The Plugin methods <tt>setup</tt>, <tt>execute</tt> and
	 * <tt>teardown</tt> are called on different threads, but they are called
	 * sequentially. This means that the execution of these methods does not
	 * overlap, they are executed one after the other.
	 *
	 * <p>
	 * Examples:
	 * <ul>
	 * <li><tt>setup</tt> (failed) -&gt; <tt>teardown</tt></li>
	 * <li><tt>execute</tt> starts, configuration changes, <tt>execute</tt>
	 * ends -&gt; <tt>teardown</tt><br>
	 * on next schedule interval: <tt>setup</tt> -&gt; <tt>execute</tt> ...</li>
	 * <li><tt>execute</tt> starts, execution duration timeout,
	 * <tt>execute</tt> stops -&gt; <tt>teardown</tt></li>
	 * <li><tt>execute</tt> starts, <tt>execute</tt> ends, schedule is
	 * removed -&gt; <tt>teardown</tt></li>
	 * </ul>
	 * Failed means that either an unhandled exception is thrown or the status
	 * returned by the method contains a non-success code.
	 *
	 *
	 * <p>
	 * All by the Plugin allocated resources should be freed in this method.
	 * Examples are opened sockets or files.
	 *
	 * @see Monitor#setup(MonitorEnvironment)
	 */	@Override
	public void teardown(MonitorEnvironment env) throws Exception {
		 HttpsURLConnection.setDefaultHostnameVerifier(defaultVerifier);	
	 }

	private void logSevere(String message) {
		if (log.isLoggable(Level.SEVERE)){
			log.severe(message);
		}
	}	 
	 	 
	 
	private void logWarn(String message) {
		if (log.isLoggable(Level.WARNING)){
			log.warning(message);
		}
	}	 
 
	private void logInfo(String message) {
		if (log.isLoggable(Level.INFO)){
			log.info(message);
		}
	}
	private void logFine(String message) {
		if (log.isLoggable(Level.FINE)){
			log.fine(message);
		}
	}
}


class TrustSSL {

    public static void trustAllCerts() throws Exception {
        // Create a trust manager that does not validate certificate chains
        TrustManager[] trustAllCerts = new TrustManager[] { new X509TrustManager() {
            public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                return null;
            }
            public void checkClientTrusted(X509Certificate[] certs, String authType) {
            }
            public void checkServerTrusted(X509Certificate[] certs, String authType) {
            }
        } };
        // Install the all-trusting trust manager
        final SSLContext sc = SSLContext.getInstance("SSL");
        sc.init(null, trustAllCerts, new java.security.SecureRandom());
        HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
        // Create all-trusting host name verifier
        HostnameVerifier allHostsValid = new HostnameVerifier() {
            public boolean verify(String hostname, SSLSession session) {
                return true;
            }
        };
        
        // Install the all-trusting host verifier
        HttpsURLConnection.setDefaultHostnameVerifier(allHostsValid);


    } 
} 
