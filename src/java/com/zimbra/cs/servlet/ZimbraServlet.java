/*
 * ***** BEGIN LICENSE BLOCK *****
 * 
 * Zimbra Collaboration Suite Server
 * Copyright (C) 2005, 2006, 2007 Zimbra, Inc.
 * 
 * The contents of this file are subject to the Yahoo! Public License
 * Version 1.0 ("License"); you may not use this file except in
 * compliance with the License.  You may obtain a copy of the License at
 * http://www.zimbra.com/license.
 * 
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied.
 * 
 * ***** END LICENSE BLOCK *****
 */

/*
 * Created on 2005. 4. 5.
 */
package com.zimbra.cs.servlet;

import java.io.IOException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.httpclient.Cookie;
import org.apache.commons.httpclient.Header;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.HttpRecoverableException;
import org.apache.commons.httpclient.HttpState;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.InputStreamRequestEntity;
import org.apache.commons.httpclient.methods.PostMethod;

import com.zimbra.common.service.ServiceException;
import com.zimbra.common.soap.Element;
import com.zimbra.common.soap.SoapProtocol;
import com.zimbra.common.util.ByteUtil;
import com.zimbra.common.util.Log;
import com.zimbra.common.util.LogFactory;
import com.zimbra.common.util.TrustedNetwork;
import com.zimbra.common.util.ZimbraLog;
import com.zimbra.cs.account.Account;
import com.zimbra.cs.account.AuthContext;
import com.zimbra.cs.account.AuthToken;
import com.zimbra.cs.account.AuthTokenException;
import com.zimbra.cs.account.Config;
import com.zimbra.cs.account.Domain;
import com.zimbra.cs.account.Provisioning;
import com.zimbra.cs.account.Server;
import com.zimbra.cs.account.Provisioning.AccountBy;
import com.zimbra.cs.account.Provisioning.DomainBy;
import com.zimbra.cs.mailbox.ACL;
import com.zimbra.cs.service.AuthProvider;
import com.zimbra.cs.util.Zimbra;

/**
 * @author jhahm
 *
 * Superclass for all Zimbra servlets.  Supports port filtering and
 * provides some utility methods to subclasses.
 */
public class ZimbraServlet extends HttpServlet {
    private static final long serialVersionUID = 5025244890767551679L;

    private static Log mLog = LogFactory.getLog(ZimbraServlet.class);

    public static final String USER_SERVICE_URI  = "/service/soap/";
    public static final String ADMIN_SERVICE_URI = "/service/admin/soap/";

    public static final String COOKIE_ZM_AUTH_TOKEN       = "ZM_AUTH_TOKEN";
    public static final String COOKIE_ZM_ADMIN_AUTH_TOKEN = "ZM_ADMIN_AUTH_TOKEN"; 

    private static final String PARAM_ALLOWED_PORTS  = "allowed.ports";

    protected static final String WWW_AUTHENTICATE_HEADER = "WWW-Authenticate";
    protected String getRealmHeader()  { return "BASIC realm=\"Zimbra\""; }
    
    protected static final String X_ORIGINATING_IP_HEADER = "X-Originating-IP";
    
    protected static final String ZIMBRA_FAULT_CODE_HEADER    = "X-Zimbra-Fault-Code";
    protected static final String ZIMBRA_FAULT_MESSAGE_HEADER = "X-Zimbra-Fault-Message";

    private static final int MAX_PROXY_HOPCOUNT = 3;

    private static Map<String, ZimbraServlet> sServlets = new HashMap<String, ZimbraServlet>();

    private int[] mAllowedPorts;

    public void init() throws ServletException {
        try {
            String portsCSV = getInitParameter(PARAM_ALLOWED_PORTS);
            if (portsCSV != null) {
                // Split on zero-or-more spaces followed by comma followed by
                // zero-or-more spaces.
                String[] vals = portsCSV.split("\\s*,\\s*");
                if (vals == null || vals.length == 0)
                    throw new ServletException("Must specify comma-separated list of port numbers for " +
                                               PARAM_ALLOWED_PORTS + " parameter");
                mAllowedPorts = new int[vals.length];
                for (int i = 0; i < vals.length; i++) {
                    try {
                        mAllowedPorts[i] = Integer.parseInt(vals[i]);
                    } catch (NumberFormatException e) {
                        throw new ServletException("Invalid port number \"" + vals[i] + "\" in " +
                                                   PARAM_ALLOWED_PORTS + " parameter");
                    }
                    if (mAllowedPorts[i] < 1)
                        throw new ServletException("Invalid port number " + mAllowedPorts[i] + " in " +
                                                   PARAM_ALLOWED_PORTS + " parameter; port number must be greater than zero");
                }
            }
            
            // Store reference to this servlet for accessor 
            synchronized (sServlets) {
                String name = getServletName();
                if (sServlets.containsKey(name)) {
                    Zimbra.halt("Attempted to instantiate a second instance of " + name);
                }
                sServlets.put(getServletName(), this);
                mLog.debug("Added " + getServletName() + " to the servlet list");
            }
        } catch (Throwable t) {
            Zimbra.halt("Unable to initialize servlet " + getServletName() + "; halting", t);
        }
    }
    
    public static ZimbraServlet getServlet(String name) {
        synchronized (sServlets) {
            return sServlets.get(name);
        }
    }

    protected boolean isRequestOnAllowedPort(HttpServletRequest request) {
        if (mAllowedPorts != null && mAllowedPorts.length > 0) {
            int incoming = request.getLocalPort();
            for (int i = 0; i < mAllowedPorts.length; i++) {
                if (mAllowedPorts[i] == incoming) {
                	return true;
                }
            }
            return false;
        }
        return true;
    }
    /**
     * Filter the request based on incoming port.  If the allowed.ports
     * parameter is specified for the servlet, the incoming port must
     * match one of the listed ports.
     */
    protected void service(HttpServletRequest request, HttpServletResponse response)
    throws ServletException, IOException {
        boolean allowed = isRequestOnAllowedPort(request);
        if (!allowed) {
        	SoapProtocol soapProto = SoapProtocol.Soap12;
        	ServiceException e = ServiceException.FAILURE("Request not allowed on port " + request.getLocalPort(), null);
        	ZimbraLog.soap.warn(null, e);
        	Element fault = SoapProtocol.Soap12.soapFault(e);
        	Element envelope = SoapProtocol.Soap12.soapEnvelope(fault);
        	byte[] soapBytes = envelope.toUTF8();
        	response.setContentType(soapProto.getContentType());
        	response.setBufferSize(soapBytes.length + 2048);
        	response.setContentLength(soapBytes.length);
        	response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        	response.getOutputStream().write(soapBytes);
        	return;
        }
        super.service(request, response);
    }

    public static AuthToken getAuthTokenFromCookie(HttpServletRequest req, HttpServletResponse resp)
    throws IOException {
        return getAuthTokenFromCookieImpl(req, resp, false, false);
    }

    public static AuthToken getAuthTokenFromCookie(HttpServletRequest req, HttpServletResponse resp, boolean doNotSendHttpError)
    throws IOException {
        return getAuthTokenFromCookieImpl(req, resp, false, doNotSendHttpError);
    }

    public static AuthToken getAdminAuthTokenFromCookie(HttpServletRequest req, HttpServletResponse resp)
    throws IOException {
        return getAuthTokenFromCookieImpl(req, resp, true, false);
    }

    public static AuthToken getAdminAuthTokenFromCookie(HttpServletRequest req, HttpServletResponse resp, boolean doNotSendHttpError)
    throws IOException {
        return getAuthTokenFromCookieImpl(req, resp, true, doNotSendHttpError);
    }

    private static AuthToken getAuthTokenFromCookieImpl(HttpServletRequest req,
                                                        HttpServletResponse resp,
                                                        boolean isAdminReq,
                                                        boolean doNotSendHttpError)
    throws IOException {
        
        AuthToken authToken = null;
        try {
            authToken = AuthProvider.getAuthToken(req, isAdminReq);
            if (authToken == null) {
                if (!doNotSendHttpError)
                    resp.sendError(HttpServletResponse.SC_UNAUTHORIZED, "no authtoken cookie");
                return null;
            }
            
            if (authToken.isExpired()) {
                if (!doNotSendHttpError)
                    resp.sendError(HttpServletResponse.SC_UNAUTHORIZED, "authtoken expired");
                return null;
            }
            return authToken;
        } catch (AuthTokenException e) {
            if (!doNotSendHttpError)
                resp.sendError(HttpServletResponse.SC_UNAUTHORIZED, "unable to parse authtoken");
            return null;
        }
    }

    
    protected void proxyServletRequest(HttpServletRequest req, HttpServletResponse resp, String accountId)
    throws IOException, ServiceException {
    	Provisioning prov = Provisioning.getInstance();
    	Account acct = prov.get(AccountBy.id, accountId);
    	if (acct == null) {
    		resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "no such user");
    		return;
    	}
    	proxyServletRequest(req, resp, prov.getServer(acct), null);
    }

    protected void proxyServletRequest(HttpServletRequest req, HttpServletResponse resp, Server server, AuthToken authToken)
    throws IOException, ServiceException {
        String uri = req.getRequestURI(), qs = req.getQueryString();
        if (qs != null)
            uri += '?' + qs;
        proxyServletRequest(req, resp, server, uri, authToken);
    }
    
    protected void proxyServletRequest(HttpServletRequest req, HttpServletResponse resp, Server server, String uri, AuthToken authToken)
    throws IOException, ServiceException {
        if (server == null) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "cannot find remote server");
            return;
        }
    	HttpMethod method;
    	String url = getServiceUrl(server, null, uri);
        if (req.getMethod().equalsIgnoreCase("GET")) {
        	method = new GetMethod(url.toString());
        } else if (req.getMethod().equalsIgnoreCase("POST") || req.getMethod().equalsIgnoreCase("PUT")) {
        	PostMethod post = new PostMethod(url.toString());
        	post.setRequestEntity(new InputStreamRequestEntity(req.getInputStream()));
        	method = post;
        } else {
        	resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "cannot proxy method: " + req.getMethod());
        	return;
        }
        HttpState state = new HttpState();
        String hostname = method.getURI().getHost();
        if (authToken != null)
            authToken.encode(state, false, hostname);              

        try {
        	proxyServletRequest(req, resp, method, state);
        } finally {
        	method.releaseConnection();
        }
    }

    protected void proxyServletRequest(HttpServletRequest req, HttpServletResponse resp, HttpMethod method, HttpState state)
    throws IOException, ServiceException {
        // create an HTTP client with the same cookies
        javax.servlet.http.Cookie cookies[] = req.getCookies();
        String hostname = method.getURI().getHost();
        if (cookies != null) {
        	for (int i = 0; i < cookies.length; i++)
        		state.addCookie(new Cookie(hostname, cookies[i].getName(), cookies[i].getValue(), "/", null, false));
        }
        HttpClient client = new HttpClient();
        if (state != null)
        	client.setState(state);

        int hopcount = 0;
        for (Enumeration enm = req.getHeaderNames(); enm.hasMoreElements(); ) {
        	String hname = (String) enm.nextElement(), hlc = hname.toLowerCase();
        	if (hlc.equals("x-zimbra-hopcount"))
        		try { hopcount = Math.max(Integer.parseInt(req.getHeader(hname)), 0); } catch (NumberFormatException e) { }
    		else if (hlc.startsWith("x-") || hlc.startsWith("content-") || hlc.equals("authorization"))
    			method.addRequestHeader(hname, req.getHeader(hname));
        }
        if (hopcount >= MAX_PROXY_HOPCOUNT)
        	throw ServiceException.TOO_MANY_HOPS();
        method.addRequestHeader("X-Zimbra-Hopcount", Integer.toString(hopcount + 1));

        // dispatch the request and copy over the results
        int statusCode = -1;
        for (int retryCount = 3; statusCode == -1 && retryCount > 0; retryCount--) {
        	try {
        		statusCode = client.executeMethod(method);
        	} catch (HttpRecoverableException e) {}
        }
        if (statusCode == -1) {
            resp.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "retry limit reached");
            return;
        } else if (statusCode >= 300) {
    		resp.sendError(statusCode, method.getStatusText());
        	return;
        }

        Header[] headers = method.getResponseHeaders();
        for (int i = 0; i < headers.length; i++) {
        	String hname = headers[i].getName(), hlc = hname.toLowerCase();
        	if (hlc.startsWith("x-") || hlc.startsWith("content-") || hlc.startsWith("www-"))
        		resp.addHeader(hname, headers[i].getValue());
        }
        ByteUtil.copy(method.getResponseBodyAsStream(), false, resp.getOutputStream(), false);
    }
    

    public Account cookieAuthRequest(HttpServletRequest req, HttpServletResponse resp, boolean doNotSendHttpError) 
    throws IOException, ServiceException {
        int adminPort = Provisioning.getInstance().getLocalServer().getIntAttr(Provisioning.A_zimbraAdminPort, -1);
        boolean isAdminRequest = (req.getLocalPort() == adminPort);
        AuthToken at = isAdminRequest ? getAdminAuthTokenFromCookie(req, resp, true) : getAuthTokenFromCookie(req, resp, true);
        return at == null ? null : Provisioning.getInstance().get(AccountBy.id, at.getAccountId(), at); 
    }

    public Account basicAuthRequest(HttpServletRequest req, HttpServletResponse resp, boolean sendChallenge)
    throws IOException, ServiceException {
        String auth = req.getHeader("Authorization");

        // TODO: more liberal parsing of Authorization value...
        if (auth == null || !auth.startsWith("Basic ")) {
            if (sendChallenge) {
                resp.addHeader(WWW_AUTHENTICATE_HEADER, getRealmHeader());            
                resp.sendError(HttpServletResponse.SC_UNAUTHORIZED, "must authenticate");
            }
            return null;
        }

        // 6 comes from "Basic ".length();
        String userPass = new String(Base64.decodeBase64(auth.substring(6).getBytes()));

        int loc = userPass.indexOf(":"); 
        if (loc == -1) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "invalid basic auth credentials");
            return null;
        }
        
        String userPassedIn = userPass.substring(0, loc);
        String user = userPassedIn;
        String pass = userPass.substring(loc + 1);

        Provisioning prov = Provisioning.getInstance();
        
        if (user.indexOf('@') == -1) {
            String host = req.getServerName();
            if (host != null) {
                Domain d = prov.get(DomainBy.virtualHostname, host.toLowerCase());
                if (d != null) user += "@" + d.getName();
            }
        }

        Account acct = prov.get(AccountBy.name, user);
        if (acct == null) {
            if (sendChallenge) {
                resp.addHeader(WWW_AUTHENTICATE_HEADER, getRealmHeader());
                resp.sendError(HttpServletResponse.SC_UNAUTHORIZED, "invalid username/password");
            }
            return new ACL.GuestAccount(user, pass);
        }
        try {
            Map<String, Object> authCtxt = new HashMap<String, Object>();
            authCtxt.put(AuthContext.AC_ORIGINATING_CLIENT_IP, getRemoteIp(req));
            authCtxt.put(AuthContext.AC_ACCOUNT_NAME_PASSEDIN, userPassedIn);
            prov.authAccount(acct, pass, "http/basic", authCtxt);
        } catch (ServiceException se) {
            if (sendChallenge) {
                resp.addHeader(WWW_AUTHENTICATE_HEADER, getRealmHeader());
                resp.sendError(HttpServletResponse.SC_UNAUTHORIZED, "invalid username/password");
            }
            return null;
        }
        return acct;
    }
    
    private static final String SCHEME_HTTP  = "http://";
    private static final String SCHEME_HTTPS = "https://";
    
    private static int DEFAULT_HTTP_PORT = 80;
    private static int DEFAULT_HTTPS_PORT = 443;

    
    public static String getServiceUrl(Account acct, String path) throws ServiceException {
        Provisioning prov = Provisioning.getInstance();
        Server server = prov.getServer(acct);
        
        if (server == null) {
            throw ServiceException.FAILURE("unable to retrieve server for account" + acct.getName(), null);
        }
        
        Domain domain = prov.getDomain(acct);
        String uri;
        Config config = prov.getConfig();
        String defaultDomain = config.getAttr(Provisioning.A_zimbraDefaultDomainName, null);
        if (defaultDomain == null || !defaultDomain.equalsIgnoreCase(acct.getDomainName()))
            uri = path + "/" + acct.getName();
        else
            uri = path + "/" + acct.getUid();
        return getServiceUrl(server, domain, uri);
    }
    

    public static String getServiceUrl(Server server, Domain domain, String path) throws ServiceException {
	return getServiceUrl(server, domain, path, true, true);
    }

    
    /**
     * Returns absolute URL with scheme, host, and port for mail app on server.
     * 
     * @param server
     * @param path what follows port number; begins with slash
     * @param preferSSL if both SSL and and non-SSL are available, whether to prefer SSL 
     * @param checkReverseProxiedMode whether to take into account if the server is running in reverse proxied mode
     * @return desired URL
     */
    public static String getServiceUrl(Server server, Domain domain, String path, boolean preferSSL, boolean checkReverseProxiedMode) throws ServiceException {
        String publicServiceHostname = domain == null ? null : domain.getAttr(Provisioning.A_zimbraPublicServiceHostname, null);
        
        String hostname;
        if (publicServiceHostname == null)
            hostname = server.getAttr(Provisioning.A_zimbraServiceHostname);
        else
            hostname = publicServiceHostname;
        
        if (hostname == null)
            throw ServiceException.INVALID_REQUEST("server " + server.getName() + " does not have " + Provisioning.A_zimbraServiceHostname, null);
        
        /*
         * We now have a hostname.  
         * if server is running in reverse proxied mode and we need to generate the URL to point to the reverse proxy, 
         * 1. domain.zimbraPublicServiceHostname is set to the hostname on which the reverse proxy is running, and 
         *      - a server can be found by zimbraPublicServiceHostname: this is the ideal case, use it
         *      - a server cannot be found by zimbraPublicServiceHostname: no good, throw ServiceException
         *         
         * 2. domain is null(really an error, but we've been handling it so keep the current code behavior) or 
         *    domain.zimbraPublicServiceHostname is not set.  
         *    This is OK, assuming the reverse proxy is running on the same server.  
         *    We do the same check (as we would do for 1) if the reverse proxy is indeed running on the configured server
         */
        Server publicServiceServer = null;
        boolean reverseProxiedMode = false;
        if (checkReverseProxiedMode && reverseProxiedMode(server)) {
            reverseProxiedMode = true;
            if (publicServiceHostname != null) {
                publicServiceServer = Provisioning.getInstance().get(Provisioning.ServerBy.serviceHostname, publicServiceHostname);
                if (publicServiceServer == null)
                    throw ServiceException.INVALID_REQUEST("server " + publicServiceHostname + " not found", null);
            } else
                publicServiceServer = server;
            
            // check if the reverse proxy is enabled, should we?
            if (!publicServiceServer.getBooleanAttr(Provisioning.A_zimbraReverseProxyHttpEnabled, false))
                throw ServiceException.INVALID_REQUEST("server " + server.getName() + " is running in reverse proxied mode " + 
                                                       "but reverse proxy is not enabled on server " + publicServiceServer.getName() +
                                                       ", either domain " + Provisioning.A_zimbraPublicServiceHostname + " is not set " + 
                                                       "or is set to a server on which reverse proxy is not enabled", 
                                                       null);
        }
        
        String modeString = server.getAttr(Provisioning.A_zimbraMailMode, null);
        if (modeString == null) {
            throw ServiceException.INVALID_REQUEST("server " + server.getName() + " does not have " + Provisioning.A_zimbraMailMode + " set, maybe it is not a store server?", null);
        }
        
        Provisioning.MAIL_MODE mode;
        try {
            mode = Provisioning.MAIL_MODE.valueOf(modeString);
        } catch (IllegalArgumentException iae) {
            throw ServiceException.INVALID_REQUEST("server " + server.getName() + " has invalid " + Provisioning.A_zimbraMailMode + ": " + modeString, iae);
        }
        
        boolean ssl;
        boolean printPort = true;
        
        switch (mode) {
        case both:
        case mixed:
        case redirect:
            ssl = preferSSL;
            break;
        case https:
            ssl = true;
            break;
        case http:
            ssl = false;
            break;
        default:
            throw ServiceException.INVALID_REQUEST("server " + server.getName() + " has unknown " + Provisioning.A_zimbraMailMode + ": " + mode, null);
        }
        
        String scheme;
        String portAttr;
        int port = 0;
        Server targetServer = (publicServiceServer == null) ? server : publicServiceServer;

        if (ssl) {
            scheme = SCHEME_HTTPS;
            if (reverseProxiedMode)
                portAttr = Provisioning.A_zimbraMailSSLProxyPort;
            else
                portAttr = Provisioning.A_zimbraMailSSLPort;
            port = targetServer.getIntAttr(portAttr, 0);
            if (port < 1) {
                throw ServiceException.INVALID_REQUEST("server " + targetServer.getName() + " has invalid " + portAttr + ": " + port, null);
            }
            if (port == DEFAULT_HTTPS_PORT)
            	printPort = false;
        } else {
            scheme = SCHEME_HTTP;
            if (reverseProxiedMode)
                portAttr = Provisioning.A_zimbraMailProxyPort;
            else
                portAttr = Provisioning.A_zimbraMailPort;
            port = targetServer.getIntAttr(portAttr, 0);
            if (port < 1) {
                throw ServiceException.INVALID_REQUEST("server " + targetServer.getName() + " has invalid " + portAttr + ": " + port, null);
            }
            if (port == DEFAULT_HTTP_PORT)
            	printPort = false;
        }

        StringBuffer sb = new StringBuffer(128);
        sb.append(scheme).append(hostname);
        if (printPort)
        	sb.append(":").append(port);
        sb.append(path);
        return sb.toString();
    }
    
    public static boolean reverseProxiedMode(Server server) throws ServiceException {
        String referMode = server.getAttr(Provisioning.A_zimbraMailReferMode, "wronghost");
        return Provisioning.MAIL_REFER_MODE_REVERSE_PROXIED.equals(referMode);
    }
    
    protected void returnError(HttpServletResponse resp, ServiceException e) {
    	resp.setHeader(ZIMBRA_FAULT_CODE_HEADER, e.getCode());
    	resp.setHeader(ZIMBRA_FAULT_MESSAGE_HEADER, e.getMessage());
    	resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    }
    
    protected String getRemoteIp(HttpServletRequest req) {
        String remoteAddr = req.getRemoteAddr();
                
        String origIp = null;
        if (TrustedNetwork.isIpTrusted(remoteAddr)) {
            origIp = req.getHeader(X_ORIGINATING_IP_HEADER);
        }
        return origIp;
    }
    
    protected void addRemoteIpToLoggingContext(HttpServletRequest req) {
        String remoteAddr = req.getRemoteAddr();
                
        String origIp = null;
        if (TrustedNetwork.isIpTrusted(remoteAddr)) {
            origIp = req.getHeader(X_ORIGINATING_IP_HEADER);
            if (origIp != null)
                ZimbraLog.addOrigIpToContext(origIp);
        }
        
        // don't log ip if oip is present and ip is localhost
        if (!TrustedNetwork.isLocalhost(remoteAddr) || origIp == null)
            ZimbraLog.addIpToContext(remoteAddr);
    }
}
