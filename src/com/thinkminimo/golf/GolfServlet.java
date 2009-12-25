package com.thinkminimo.golf;

import org.json.JSONStringer;
import org.json.JSONException;

import java.io.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.*;
import java.util.regex.Pattern;
import net.sourceforge.htmlunit.corejs.javascript.*;

import java.net.*;

import javax.servlet.*;
import javax.servlet.http.*;

import org.mortbay.jetty.servlet.DefaultServlet;

import com.gargoylesoftware.htmlunit.*;
import com.gargoylesoftware.htmlunit.html.*;
import com.gargoylesoftware.htmlunit.xml.*;
import com.gargoylesoftware.htmlunit.javascript.*;

/**
 * Golf servlet class!
 */
public class GolfServlet extends HttpServlet {
  
  public static final int     LOG_ALL                 = 0;
  public static final int     LOG_TRACE               = 1;
  public static final int     LOG_DEBUG               = 2;
  public static final int     LOG_INFO                = 3;
  public static final int     LOG_WARN                = 4;
  public static final int     LOG_ERROR               = 5;
  public static final int     LOG_FATAL               = 6;
  public static final int     LOG_NONE                = 999;

  public static final int     JSVM_TIMEOUT            = 10000;

  private class StoredJSVM {
    public WebClient client;
    public HtmlPage  lastPage;
    public long      lastAccessTime;

    StoredJSVM(WebClient client) {
      this.client   = client;
      this.lastPage = null;
      this.lastAccessTime = (new Date()).getTime();
    }
  }

  public static class RedirectException extends Exception {
    public RedirectException(String msg) {
      super(msg);
    }
  }

  public static class PermanentRedirectException extends RedirectException {
    public PermanentRedirectException(String msg) {
      super(msg);
    }
  }

  private class GolfSession {
    private HttpSession mSess;

    public GolfSession(HttpServletRequest req) { 
      mSess = req.getSession(true); 
    }

    private String get(String name) { 
      return (String) mSess.getAttribute(name);
    }

    private void set(String name, String value) { 
      mSess.setAttribute(name, value);
    }

    public Integer getSeq() { 
      try { 
        return Integer.parseInt(get("golf"));
      } catch (NumberFormatException e) { }
      return null;
        
    }
    public void setSeq(Integer value) {
      set("golf", String.valueOf(value));
    }

    public Boolean getJs() {
      return get("js") == null ? null : Boolean.parseBoolean(get("js"));
    }
    public void setJs(boolean value) {
      set("js", String.valueOf(value));
    }

    public String getIpAddr() {
      return get("ipaddr");
    }
    public void setIpAddr(String value) {
      set("ipaddr", value);
    }

    public String getLastUrl() {
      return get("lasturl");
    }
    public void setLastUrl(String value) {
      set("lasturl", value);
    }

    public String getLastEvent() {
      return get("lastevent");
    }
    public void setLastEvent(String value) {
      set("lastevent", value);
    }

    public String getLastTarget() {
      return get("lasttarget");
    }
    public void setLastTarget(String value) {
      set("lasttarget", value);
    }

    public Boolean getForceClient() {
      return get("forceclient") == null 
                ? null 
                : Boolean.parseBoolean(get("forceclient"));
    }
    public void setForceClient(Boolean value) {
      set("forceclient", String.valueOf(value));
    }
    public Boolean getForceProxy() {
      return get("forceproxy") == null 
                ? null 
                : Boolean.parseBoolean(get("forceproxy"));
    }
    public void setForceProxy(Boolean value) {
      set("forceproxy", String.valueOf(value));
    }
    public Boolean getForceBot() {
      return get("forceproxy") == null 
                ? null 
                : Boolean.parseBoolean(get("forcebot"));
    }
    public void setForceBot(Boolean value) {
      set("forcebot", String.valueOf(value));
    }
  }

  private class GolfParams {
    private String    mEvent      = null;
    private String    mTarget     = null;
    private Boolean   mForce      = false;
    private Integer   mSeq        = -1;
    private Boolean   mJs         = false;
    private String    mPath       = null;
    private Boolean   mReload     = false;

    public GolfParams(HttpServletRequest req) {
      mEvent      = req.getParameter("event");
      mTarget     = req.getParameter("target");
      mForce      = req.getParameter("force") == null 
                      ? null
                      : Boolean.parseBoolean(req.getParameter("force"));
      mSeq        = req.getParameter("golf") == null 
                      ? null
                      : Integer.valueOf(req.getParameter("golf"));
      mJs         = req.getParameter("js") == null 
                      ? null
                      : Boolean.parseBoolean(req.getParameter("js"));
      mPath       = req.getParameter("path");
      mReload     = req.getParameter("reload") == null 
                      ? null
                      : Boolean.parseBoolean(req.getParameter("reload"));
    }

    public String   getEvent()          { return mEvent; }
    public String   getTarget()         { return mTarget; }
    public Boolean  getForce()          { return mForce; }
    public Integer  getSeq()            { return mSeq; }
    public Boolean  getJs()             { return mJs; }
    public String   getPath()           { return mPath; }
    public Boolean  getReload()         { return mReload; }

    public void setEvent(String v)      { mEvent      = v; }
    public void setTarget(String v)     { mTarget     = v; }
    public void setForce(Boolean v)     { mForce      = v; }
    public void setSeq(Integer v)       { mSeq        = v; }
    public void setJs(Boolean v)        { mJs         = v; }
    public void setPath(String v)       { mPath       = v; }
    public void setReload(Boolean v)    { mReload     = v; }

    private String toQueryParam(String name, String p) {
      return p != null ? name+"="+p : "";
    }
    private String toQueryParam(String name, Boolean p) {
      return p != null ? name+"="+p.toString() : "";
    }
    private String toQueryParam(String name, Integer p) {
      return p != null ? name+"="+p.toString() : "";
    }

    public String toQueryString() {
      String result = "";
      result += toQueryParam("event",     mEvent);
      result += toQueryParam("target",    mTarget);
      result += toQueryParam("force",     mForce);
      result += toQueryParam("golf",      mSeq);
      result += toQueryParam("js",        mJs);
      result += toQueryParam("path",      mPath);
      result += toQueryParam("reload",    mReload);
      return result.length() > 0 ? "?"+result : "";
    }
  }

  /**
   * Contains state info for a golf request. This is what should be passed
   * around rather than the raw request or response.
   */
  public class GolfContext {
    
    public HttpServletRequest   request     = null;
    public HttpServletResponse  response    = null;
    public GolfParams           p           = null;
    public GolfSession          s           = null;
    public String               servletUrl  = null;
    public String               urlHash     = null;
    public BrowserVersion       browser     = BrowserVersion.FIREFOX_2;
    public StoredJSVM           jsvm        = null;
    public Boolean              logging     = true;

    /**
     * Constructor.
     *
     * FIXME implement this as a singleton
     *
     * @param       request     the http request object
     * @param       response    the http response object
     */
    public GolfContext(HttpServletRequest request, 
        HttpServletResponse response) {
      this.request     = request;
      this.response    = response;
      this.p           = new GolfParams(request);
      this.s           = new GolfSession(request);
      this.urlHash     = request.getPathInfo();
      this.servletUrl  = request.getRequestURL().toString()
                            .replaceFirst(";jsessionid=.*$", "");
    }

    public void init() throws ServletException, RedirectException {
      try {
        servletUrl  = URLDecoder.decode(servletUrl, "UTF-8");
      } catch (UnsupportedEncodingException e) {
        throw new ServletException(e);
      }

      // this is why this should be a singleton!
      if (p.getReload() != null && p.getReload().booleanValue()) {
        log(this, LOG_INFO, "RELOAD via query parameter");
        request.getSession(true).invalidate();
        mJsvms.remove(request.getSession().getId());
        throw new RedirectException(servletUrl);
      }

      String origRequestUrl = servletUrl;

      // some servlet containers have been known to produce a null urlHash
      if (urlHash == null || urlHash.equals("/"))
        urlHash = "";

      // ensure that the URL is in the standard form

      // http://example.com/app//some/path/ ==>>
      //    servletUrl => http://example.com/app/
      //    urlHash    => /some/path/

      if (!servletUrl.endsWith("/")) {
        urlHash += "/";
        servletUrl += "/";
      }

      servletUrl  = servletUrl.replaceFirst(Pattern.quote(urlHash)+"$", "/")
                      .replaceFirst("/+$", "/");
      urlHash     = urlHash.replaceFirst("^/+", "/");

      String newUrl = servletUrl + urlHash;

      if (!origRequestUrl.equals(newUrl)) {
        //System.err.println("{{{ REDIRECT 0 }}}");
        throw new PermanentRedirectException(
            response.encodeRedirectURL(response.encodeRedirectURL(newUrl)));
      }

      jsvm = mJsvms.get(request.getSession().getId());

      if (jsvm == null)
        jsvm = new StoredJSVM((WebClient) null);
    }

    public boolean hasEvent() {
      return (this.p.getEvent() != null && this.p.getTarget() != null);
    }
  }

  private static ConcurrentHashMap<String, StoredJSVM> mJsvms =
    new ConcurrentHashMap<String, StoredJSVM>();

  private static int                  mLogLevel     = LOG_ALL;
  private static String               mNewHtml      = null;
  private static String               mNewHtmlFc    = null;
  private static String               mErrorPage    = null;
  private static String               mJsDetect     = null;
  private static String               mComponents   = null;
  private static String               mDevMode      = null;
  private static String               mPoolSize     = null;
  private static String               mPoolExpire   = null;
  private static AtomicBoolean        mBotMutex     = new AtomicBoolean();
  private static ArrayList<String>    mForceProxy   = new ArrayList<String>();
  private static ArrayList<String>    mForceClient  = new ArrayList<String>();
  private static ArrayList<String>    mForceBot     = new ArrayList<String>();

  /**
   * @see javax.servlet.Servlet#init(javax.servlet.ServletConfig)
   */
  public void init(ServletConfig config) throws ServletException {
    super.init(config); // tricky little guy

    // init parameters
    mDevMode    = config.getInitParameter("devmode");
    mPoolSize   = config.getInitParameter("poolsize");
    mPoolExpire = config.getInitParameter("poolexpire");

    // default values
    mDevMode    = (mDevMode    != null ? mDevMode    : "true" );
    mPoolSize   = (mPoolSize   != null ? mPoolSize   : "10"   );
    mPoolExpire = (mPoolExpire != null ? mPoolExpire : "900"  );  // 15 min
    
    // set initial values
    mBotMutex.set(false);

    // process the static files that need to be kept in memory
    cacheStaticFiles();
  }

  /**
   * Serve http requests!
   *
   * @param       request     the http request object
   * @param       response    the http response object
   */
  public void service(HttpServletRequest request, HttpServletResponse response)
      throws IOException, ServletException {
    GolfContext   context   = null;
    String        result    = null;

    // All query string parameters are considered to be arguments directed
    // to the golf container. The app itself gets its arguments in the path
    // info.

    try {
      // create the context object for this request
      context = new GolfContext(request, response);

      // log the incoming request before any manipulation is done
      logRequest(context);

      // initialize context
      context.init();

      String url = context.request.getRequestURL().toString()
        .replaceFirst(";jsessionid=.*$", "");

      if (! url.endsWith("/")) {
        //System.err.println("{{{ REDIRECT 7 }}}");
        throw new PermanentRedirectException(
            context.response.encodeRedirectURL(url + "/"));
      }

      // handle your business
      if (context.p.getPath() != null)
        doStaticResourceGet(context);
      else
        doDynamicResourceGet(context);
    }

    catch (PermanentRedirectException r) {
      // 301 PERMANENTLY MOVED
      logResponse(context, 301);
      log(context, LOG_INFO, "301 ---to--> "+r.getMessage());
      context.response.setHeader("Location", r.getMessage());
      context.response.sendError(301);
    }

    catch (RedirectException r) {
      // 302 FOUND
      logResponse(context, 302);
      log(context, LOG_INFO, "302 ---to--> "+r.getMessage());
      context.response.sendRedirect(r.getMessage());
    }

    catch (FileNotFoundException e) {
      // 404 NOT FOUND
      logResponse(context, 404);
      errorPage(context, HttpServletResponse.SC_NOT_FOUND, e);
    }

    catch (Exception x) {
      // 500 INTERNAL SERVER ERROR
      logResponse(context, 500);
      x.printStackTrace();
      errorPage(context, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, x);
    }
  }

  /**
   * (Re)build static files and cache them in memory
   */
  public void cacheStaticFiles() throws ServletException {
    try {
      if (Boolean.parseBoolean(mDevMode)) {
        Main.cacheComponentsFile();
        Main.cacheNewDotHtmlFile();
      }
      mNewHtml  =
        (new GolfResource(getServletContext(), Main.NEW_HTML)).toString();
      mNewHtmlFc  =
        (new GolfResource(getServletContext(), Main.NEW_FC_HTML)).toString();
      mErrorPage =
        (new GolfResource(getServletContext(), Main.ERROR_HTML)).toString();
      mJsDetect = 
        (new GolfResource(getServletContext(), Main.JSDETECT_HTML)).toString();
      mComponents =
        (new GolfResource(getServletContext(), Main.COMPONENTS_JS)).toString();

      try {
        // wait for resource to be available
        while (!mBotMutex.compareAndSet(false, true));

        mForceProxy =
          (new GolfResource(getServletContext(), Main.FORCEPROXY_TXT))
            .toArrayList();

        mForceClient =
          (new GolfResource(getServletContext(), Main.FORCECLIENT_TXT))
            .toArrayList();

        mForceBot =
          (new GolfResource(getServletContext(), Main.FORCEBOT_TXT))
            .toArrayList();

      } catch (FileNotFoundException fx) {
      } finally {
        mBotMutex.set(false);
      }
    } catch (Exception e) {
      throw new ServletException("can't cache static files", e);
    }
  }

  /**
   * Do text processing of html to inject server/client specific things, etc.
   *
   * @param       page        the html page contents
   * @param       context     the golf request object
   * @param       server      whether to process for serverside or clientside
   * @return                  the processed page html contents
   */
  private static String preprocess(String page, GolfContext context, boolean server) {
    String sid = context.request.getSession().getId();

    // pattern matching all script tags (should this be removed?)
    String pat1 = "<noscript>.*</noscript>";

    // pattern matching all script tags (should this be removed?)
    String pat2 = 
      "<script type=\"text/javascript\"[^>]*>([^<]|//<!\\[CDATA\\[)*</script>";

    // document type: xhtml
    String dtd = 
      "<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Strict//EN\"\n" +
      "\"http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd\">\n";

    // remove xml tag (why is it even there?)
    if (!server)
      page = page.replaceFirst("^<\\?xml [^>]+>\n", "");

    // robots must not index event proxy (because infinite loops, etc.)
    if (!context.hasEvent())
      page = page.replaceFirst("noindex,nofollow", "index,follow");

    // remove the golfid attribute as it's not necessary on the client
    // and it is frowned upon by the w3c validator
    if (!server)
      page = page.replaceAll("(<[^>]+) golfid=\"[0-9]+\"", "$1");

    if (! context.s.getJs().booleanValue() && !server) {
      // proxy mode: remove javascript/noscript except for serverside
      page = page.replaceAll(pat1, "");
      page = page.replaceAll(pat2, "");

      for (int i=0, j=0; (i=page.indexOf("<style", i)) != -1; i=j) {
        j = page.indexOf("</style>", i);
        page = page.substring(0, i)
          + page.substring(i, j).replaceAll("&gt;", ">") 
                                .replaceAll("&lt;", "<")
                                .replaceAll("&amp;", "&")
                                .replaceAll("&apos;", "'")
                                .replaceAll("\n", "") // FIXME this is sketchy
          + page.substring(j);
      }

      if (context.s.getForceBot().booleanValue()) {
        page = page.replaceAll("(<[^>]+) style=\"[^\"]*\"", "$1");
        page = page.replaceAll("(<[^>]+) style='[^']*'",    "$1");
        page = page.replaceAll("(<[^>]+) class=\"[^\"]*\"", "$1");
        page = page.replaceAll("(<[^>]+) class='[^']*'",    "$1");
      }
    } else {
      // on the client window.serverside must be false, and vice versa
      page = page.replaceFirst("(window.serverside +=) [a-zA-Z_]+;", 
          "$1 " + (server ? "true" : "false") + ";");

      // import the session ID into the javascript environment
      page = page.replaceFirst("(window.sessionid +=) \"[a-zA-Z_]+\";", 
          "$1 \"" + sid + "\";");
      
      // the servlet url (shenanigans here)
      page = page.replaceFirst("(window.servletUrl +=) \"[a-zA-Z_]+\";", 
          "$1 \"" + context.servletUrl + "\";");
      
      // the url fragment (shenanigans here)
      page = page.replaceFirst("(window.urlHash +=) \"[a-zA-Z_]+\";", 
          "$1 \"" + context.urlHash + "\";");
      
      // bot mode forced?
      page = page.replaceFirst("(window.forcebot +=) [a-zA-Z_]+;", 
          "$1 " + context.s.getForceBot().toString() + ";");
      
      // proxy mode forced?
      page = page.replaceFirst("(window.forceproxy +=) [a-zA-Z_]+;", 
          "$1 " + context.s.getForceProxy().toString() + ";");
      
      // client mode forced?
      page = page.replaceFirst("(window.forceclient +=) [a-zA-Z_]+;", 
          "$1 " + context.s.getForceClient().toString() + ";");
    }

    // no dtd for serverside because it breaks the xml parser
    return (server ? "" : dtd) + page;
  }

  /**
   * Show error page.
   *
   * @param     context   the golf request context
   * @param     e         the exception
   */
  public void errorPage(GolfContext context, int status, Exception e) {
    try {
      PrintWriter out = context.response.getWriter();

      String errHtml = 
        mErrorPage.replaceAll("<%error%>", HTMLEntityEncode(e.getMessage()));

      context.response.setStatus(status);
      context.response.setContentType("text/html");

      out.print(errHtml);
    } catch (Exception x) {
      x.printStackTrace();
    }
  }

  /**
   * Send a proxied response.
   *
   * @param   context       the golf context for this request
   */
  private void doProxy(GolfContext context) throws FileNotFoundException,
          IOException, URISyntaxException, RedirectException, ServletException {
    String      sid     = context.request.getSession().getId();
    HtmlPage    result  = context.jsvm.lastPage;

    String      path    = context.urlHash;
    String      event   = context.p.getEvent();
    String      target  = context.p.getTarget();
    WebClient   client  = context.jsvm.client;

    String      lastEvent   = context.s.getLastEvent();
    String      lastTarget  = context.s.getLastTarget();
    String      lastUrl     = context.s.getLastUrl();

    context.jsvm.lastPage = null;
    context.s.setLastEvent(null);
    context.s.setLastTarget(null);
    context.s.setLastUrl(null);

    if (result == null || !path.equals(lastUrl)) {
      if (lastEvent == null || lastTarget == null || !path.equals(lastUrl)) {
        if (event != null && target != null && client != null) {

          // update last access time
          context.jsvm.lastAccessTime = (new Date()).getTime();

          if (event.equals("onclick")) {
            // nothing here
          } else if (event.equals("onsubmit")) {
            Map<String, String[]> pmap = context.request.getParameterMap();
            for (String key : pmap.keySet()) {
              String val = pmap.get(key)[0].replaceAll("[\"]", "\\x22");

              String script = "jQuery(\"[name='"+key+"']\").val(\""+val+"\");";

              // removed components update due to issues
              if (Boolean.parseBoolean(mDevMode))
                script = mComponents + script;
              result = (HtmlPage) client.getCurrentWindow().getEnclosedPage();
              result.executeJavaScript(script);
            }
          } else {
            throw new ServletException("unsupported event for proxy: "+event);
          }
          context.s.setLastEvent(event);
          context.s.setLastTarget(target);
          context.s.setLastUrl(path);
          if (context.request.getQueryString() != null) {
            //System.err.println("{{{ REDIRECT 1 }}}");
            throw new RedirectException(
                context.response.encodeRedirectURL(context.servletUrl + path));
          } else {
            lastEvent   = context.s.getLastEvent();
            lastTarget  = context.s.getLastTarget();
            lastUrl     = context.s.getLastUrl();
          }
        } else if (client == null) {
          log(context, LOG_INFO, "*** INITIALIZING NEW CLIENT ***");
          log(context, LOG_INFO, "Running JSVMs, before GC: " + mJsvms.size());

          createNewJsvm(context);

          log(context, LOG_INFO, "Running JSVMs, after GC:  " + mJsvms.size());

          client = context.jsvm.client;

          // write any alert() calls to the log
          client.setAlertHandler(new AlertHandler() {
            public void handleAlert(Page page, String message) {
              System.err.println("ALERT: " + message);
            }
          });

          // if this isn't long enough it'll timeout before all ajax is complete
          client.setJavaScriptTimeout(JSVM_TIMEOUT);

          // the blank skeleton html template
          String newHtml = mNewHtml;

          // do not pass query string to the app, as those parameters are meant
          // only for the golf container itself.

          StringWebResponse response = new StringWebResponse(
            preprocess(newHtml, context, true),
            new URL(context.servletUrl + "#" + context.urlHash)
          );

          // run it through htmlunit
          result = (HtmlPage) context.jsvm.client.loadWebResponseInto(
            response,
            client.getCurrentWindow()
          );
        } else {
          String script = "jQuery.address.value('"+context.urlHash+"');";
          // removed components update due to issues
          if (Boolean.parseBoolean(mDevMode))
            script = mComponents + script;
          result = (HtmlPage) client.getCurrentWindow().getEnclosedPage();
          result.executeJavaScript(script);
        }
      }

      if (lastEvent != null && lastTarget != null && path.equals(lastUrl)) {
        if (client != null) {
          String script;
          if (lastEvent.equals("onclick")) {
            script = "jQuery(\"[golfid='"+lastTarget+"']\").click()";
          } else if (lastEvent.equals("onsubmit")) {
            script = "jQuery(\"[golfid='"+lastTarget+"']\").submit()";
          } else {
            //System.err.println("{{{ REDIRECT 2 }}}");
            throw new RedirectException(
                context.response.encodeRedirectURL(context.servletUrl + path));
          }
          // removed components update due to issues
          if (Boolean.parseBoolean(mDevMode))
            script = mComponents + script;
          result = (HtmlPage) client.getCurrentWindow().getEnclosedPage();
          result.executeJavaScript(script);
        } else {
          //System.err.println("{{{ REDIRECT 3 }}}");
          throw new RedirectException(
              context.response.encodeRedirectURL(context.servletUrl + path));
        }
      }

      String loc  = (String) result.executeJavaScript("window.location.href")
                              .getJavaScriptResult();

      try {
        loc = URLDecoder.decode(loc, "UTF-8");
      } catch (UnsupportedEncodingException e) {
        throw new ServletException(e);
      }

      if (!loc.startsWith(context.servletUrl)) {
        //System.err.println("{{{ REDIRECT 4 }}}");
        throw new RedirectException(
            context.response.encodeRedirectURL(loc));
      } else {
        loc = loc.replaceFirst("^[^#]+#", "");
      }
      
      if (!loc.equals(path) || context.request.getQueryString() != null) {
        context.jsvm.lastPage = result;
        context.s.setLastUrl(loc);
        //System.err.println("{{{ REDIRECT 5 }}}");
        throw new RedirectException(
            context.response.encodeRedirectURL(context.servletUrl + loc));
      }
    }

    Iterator<HtmlAnchor> anchors = result.getAnchors().iterator();
    while (anchors.hasNext()) {
      HtmlAnchor a = anchors.next();
      a.setAttribute("href",context.response.encodeURL(a.getHrefAttribute()));
    }

    result.executeJavaScript(
      "jQuery('.component').each( " +
        "function() { "+
          "var elem = jQuery(this).children().eq(0); "+
          "if (elem) { "+
            "jQuery.golf.jss.doit(elem, true); "+
          "} "+
        "} "+
      ")"
    );
              
    String html = preprocess(result.asXml(), context, false);
    sendResponse(context, html, "text/html", false);
  }

  /**
   * Send a non-proxied response.
   *
   * @param   context       the golf context for this request
   */
  private void doNoProxy(GolfContext context) throws Exception {
    // the blank skeleton html template
    String html = (context.s.getForceClient() ? mNewHtmlFc : mNewHtml);
    sendResponse(context, preprocess(html, context, false), "text/html", true);
  }

  /**
   * First clean out any old JSVMs that might be hanging around, then 
   * make sure there is room for another jsvm.
   */
  private void createNewJsvm(GolfContext context) throws ServletException {
    int   psize = Integer.parseInt(mPoolSize);
    long  ptime = Long.parseLong(mPoolExpire) * 1000L; // convert sec --> msec
    long  ctime = (new Date()).getTime();

    for (String key : mJsvms.keySet())
      if (ctime - mJsvms.get(key).lastAccessTime > ptime)
        mJsvms.remove(key);

    if (mJsvms.size() >= psize) {
      throw new ServletException(
          "The server has too many concurrent proxy sessions right now. "+
          "Please enable javascript in your browser (if you can) or try "+
          "again later."
      );
    }

    context.jsvm.client = new WebClient(context.browser);
    mJsvms.put(context.request.getSession().getId(), context.jsvm);
  }

  /**
   * Do the dynamic request.
   *
   * @param   context       the golf context for this request
   */
  private void doDynamicResourceGet(GolfContext context) throws Exception {

    HttpSession session     = context.request.getSession();
    String      remoteAddr  = context.request.getRemoteAddr();
    String      sessionAddr = context.s.getIpAddr();
    Boolean     forcebot    = context.s.getForceBot();
    Boolean     forceproxy  = context.s.getForceProxy();
    Boolean     forceclient = context.s.getForceClient();
    Boolean     forceParam  = context.p.getForce();
    Boolean     jsParam     = context.p.getJs();
    String      sid         = session.getId();
    String      uagent      = context.request.getHeader("User-Agent");

    if (sid == null)
      throw new Exception("sid is null");

    if (Boolean.parseBoolean(mDevMode)) {
      cacheStaticFiles();
      context.s.setForceProxy(forceproxy = null);
      context.s.setForceClient(forceclient = null);
    }

    // forcebot: Match regex to user agent string.
    // If match, treat user agent as a bot (no css,
    // no presentation cruft, cleaner markup).

    // FIXME: store this info in session unless in devmode, maybe
    if (forcebot == null) {
      forcebot = multipatternMatch(uagent, mForceBot);
      context.s.setForceBot(forcebot);
    }
    
    // forceproxy: Match regex to user agent string.
    // If match, force proxy mode immediately. Forcebot
    // implies forceproxy.

    // FIXME: store this info in session unless in devmode, maybe
    if (forceproxy == null) {
      if (forceproxy = 
          (multipatternMatch(uagent, mForceProxy) || forcebot)) {
        context.s.setJs(false);
        context.s.setSeq(1);
      }
      context.s.setForceProxy(forceproxy);
    }

    // forceclient: Match regex to user agent string.
    // If match, force client mode immediately, unless
    // forceproxy is set (forceproxy has higher precedence).

    // FIXME: store this info in session unless in devmode, maybe
    if (forceclient == null) {
      forceclient = false;
      if (forceproxy == false) {
        if (forceclient = multipatternMatch(uagent, mForceClient)) {
          context.s.setJs(true);
          context.s.setSeq(1);
        }
      }
      context.s.setForceClient(forceclient);
    }

    Boolean forceUa = forceproxy || forceclient;

    if (! session.isNew() || forceUa) {
      if (!forceUa && forceParam != null && forceParam.booleanValue())
        context.s.setSeq(0);

      int seq = (context.s.getSeq() == null ? 0 : (int) context.s.getSeq());
      
      context.s.setSeq(++seq);

      if (forceUa || sessionAddr != null && sessionAddr.equals(remoteAddr)) {
        if (!forceUa && seq == 1) {
          if (jsParam != null) {
            String uri;
            boolean js = jsParam.booleanValue();

            context.s.setJs(js);

            if (js) {
              uri = context.servletUrl + 
                (context.urlHash.length() > 0 ? "#" + context.urlHash : "");
            } else {
              uri = context.request.getRequestURI();
            }
            
            //System.err.println("{{{ REDIRECT 6 }}}");
            throw new RedirectException(
                context.response.encodeRedirectURL(uri));
          }
        } else if (forceUa || seq >= 2) {
          if (forceUa || context.s.getJs() != null) {
            // FIXME: lurking NPE if you change this
            if (forceclient ||
                (!forceproxy && context.s.getJs().booleanValue()))
              doNoProxy(context);
            else
              doProxy(context);
            return;
          }
        }
      }

      session.invalidate();
      context.s = new GolfSession(context.request);
    }

    context.s.setSeq(new Integer(0));
    context.s.setIpAddr(remoteAddr);

    String jsDetect = mJsDetect;

    jsDetect = 
      jsDetect.replaceAll("__HAVE_JS__", ";jsessionid="+sid+"?js=true");
    jsDetect = 
      jsDetect.replaceAll("__DONT_HAVE_JS__", ";jsessionid="+sid+"?js=false");

    sendResponse(context, jsDetect, "text/html", false);
  }

  private boolean multipatternMatch(String ua, ArrayList<String> pats) {
    for (String i : pats)
      if (ua.matches(i))
        return true;
    return false;
  }

  private void sendResponse(GolfContext context, String html, 
      String contentType, boolean canCache) throws IOException {
    context.response.setContentType(contentType);

    if (canCache)
      setCachable(context);

    PrintWriter out = context.response.getWriter();
    out.print(html);
    out.close();
    logResponse(context, 200);
  }

  private void setCachable(GolfContext context) {
    long currentTime = System.currentTimeMillis();
    long later       = 24*60*60*1000; // a day (milliseconds)
    context.response.setDateHeader("Expires", currentTime + later);
    context.response.setHeader("Cache-Control", "max-age=3600,public");
  }

  /**
   * Handle a request for a static resource.
   *
   * @param   context       the golf context for this request
   */
  private void doStaticResourceGet(GolfContext context) 
      throws FileNotFoundException, IOException, JSONException {
    String path = context.p.getPath();

    if (path.matches("^[/]*\\."))
      throw new FileNotFoundException();

    if (! path.startsWith("/"))
      path = "/" + path;

    GolfResource res = new GolfResource(getServletContext(), path);

    context.response.setContentType(res.getMimeType());
    setCachable(context);

    if (res.getMimeType().startsWith("text/")) {
      PrintWriter out = context.response.getWriter();
      out.print(res.toString());
    } else {
      OutputStream out = context.response.getOutputStream();
      out.write(res.toByteArray());
    }

    logResponse(context, 200);
  }

  /**
   * Format a nice log message.
   *
   * @param     context     the golf context for this request
   * @param     s           the log message
   * @return                the formatted log message
   */
  private String fmt(GolfContext context, String s) {
    String sid = null;
    String ip  = null;

    if (context != null) {
      sid = context.request.getSession().getId();
      ip  = context.request.getRemoteHost();
    }

    sid     = sid != null ? "[" + sid.toUpperCase().replaceAll(
                "(...)(?=...)", "$1.") + "] " : "";
    ip      = ip != null ? ip+"\n    >>> " : "";

    return sid + ip + s;
  }

  /**
   * Send a formatted message to the logs.
   *
   * @param     context     the golf context for this request
   * @param     level       the severity of the message (LOG_TRACE to LOG_FATAL)
   * @param     s           the log message
   */
  public void log(GolfContext context, int level, String s) {
    ServletContext c = getServletContext();
    if (context.logging && mLogLevel <= level)
      c.log(fmt(context, s));
  }

  /**
   * Logs a http servlet request.
   *
   * @param     context     the golf context for this request
   */
  private void logRequest(GolfContext context) {
    String method = context.request.getMethod();
    String path   = context.request.getPathInfo();
    String query  = context.request.getQueryString();
    String host   = context.request.getRemoteHost();
    String sid    = context.request.getSession().getId();
    String uagent = context.request.getHeader("User-Agent");

    String line   = method + " " + path + (query != null ? "?" + query : "");

    log(context, LOG_INFO, line);
    log(context, LOG_INFO, uagent);

    //System.out.println("||||||||||||||||||||||||||||||||||||||");
    //Enumeration headerNames = context.request.getHeaderNames();
    //while(headerNames.hasMoreElements()) {
    //  String headerName = (String)headerNames.nextElement();
    //  System.out.println("||||||||||| " + headerName + ": "
    //    + context.request.getHeader(headerName));
    //}
    //System.out.println("||||||||||||||||||||||||||||||||||||||");

  }

  /**
   * Logs a http servlet response.
   *
   * @param     context     the golf context for this request
   */
  private void logResponse(GolfContext context, int status) {
    String method = String.valueOf(status);
    String path   = context.request.getPathInfo();
    String query  = context.request.getQueryString();
    String host   = context.request.getRemoteHost();
    String sid    = context.request.getSession().getId();

    String line   = method + " " + path + (query != null ? "?" + query : "");

    log(context, LOG_INFO, line);
  }

  /**
   * Convenience function to do html entity encoding.
   *
   * @param     s         the string to encode
   * @return              the encoded string
   */
  public static String HTMLEntityEncode(String s) {
    StringBuffer buf = new StringBuffer();
    int len = (s == null ? -1 : s.length());

    for ( int i = 0; i < len; i++ ) {
      char c = s.charAt( i );
      if ( c>='a' && c<='z' || c>='A' && c<='Z' || c>='0' && c<='9' ) {
        buf.append( c );
      } else {
        buf.append("&#" + (int)c + ";");
      }
    }

    return buf.toString();
  }

  /**
   *
   */
  public static boolean isSafeName(String name) {
    return name.matches("^[a-zA-Z][a-zA-Z0-9-_.]*$");
  }

  /**
   *
   */
  public static boolean isSafeGolfId(String golfid) {
    try { 
      Integer.parseInt(golfid);
    } catch (NumberFormatException e) {
      return false;
    }
    return true;
  }

  /**
   *
   */
  public static boolean isSafeEvent(String event) {
    return (event.equals("onclick") || event.equals("onsubmit"));
  }
}
