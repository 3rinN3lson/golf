
function Component() {
  this._dom = null;
}

function Model() {
}

function Data() {
}

// install override on the jQ bind method to inject proxy links and golfIDs

if (serverside) {
  (function() {
    jQuery.fn.bind = (function(bind) {
      var lastId = 0;
      return function(name, fn) {
        var jself = jQuery(this);
        if (name == "click") {
          ++lastId;
          jself.attr("golfid", lastId);
          var e = "onclick";
          var a = "<a rel='nofollow' class='golfproxylink' href='?target="+
            lastId+"&amp;event=onclick'></a>";
          jself.wrap(a);
        } else if (name == "submit") {
          if (!jself.attr("golfid")) {
            ++lastId;
            jself.attr("golfid", lastId);
            jself.append(
              "<input type='hidden' name='event' value='onsubmit'/>");
            jself.append(
              "<input type='hidden' name='target' value='"+lastId+"'/>");
            if (!jQuery.golf.events[lastId])
              jQuery.golf.events[lastId] = [];
          }
          jQuery.golf.events[jself.attr("golfid")].push(fn);
        }
        return bind.call(jQuery(this), name, fn);
      };
    })(jQuery.fn.bind);

    jQuery.fn.trigger = (function(trigger) {
      return function(type, data) {
        var jself = jQuery(this);
        // FIXME: this is here because hunit stops firing js submit events
        if (type == "submit") {
          var tmp = jQuery.golf.events[jself.attr("golfid")];
          return jQuery.each(tmp, function(){
            this.call(jself, type, data);
          });
        } else {
          return trigger.call(jQuery(this), type, data);
        }
      };
    })(jQuery.fn.trigger);

    jQuery.ajax = (function(ajax) {
        return function(options) {
          options.async = false;
          return ajax(options);
        };
    })(jQuery.ajax);

    /*
    var fns = { 
      show:["fadeIn", "slideDown"],
      hide:["slideUp", "fadeOut"],
      toggle: ["slideToggle"]
    };

    for (var proxyreplace in fns) {
      var clientfns = fns[proxyreplace];
      for (var clientfn in clientfns) {
        jQuery.fn[clientfn] = (function(clientfn) {
          return function() {
            if (!serverside)
              return clientfn(arguments);
            else
              return this[proxyreplace]();
          };
        })(jQuery.fn[clientfn]);
      }
    }
    */
  })();
}

// install overrides on jQ DOM manipulation methods to incorporate components

(function() {
    jQuery.each([
        "append",
        "prepend",
        "after",
        "before",
        "replaceWith"
      ], function(k,v) {
        jQuery.fn[v] = (function(orig) {
            return function(a) { 
              var e = jQuery(a instanceof Component ? a._dom : a);
              jQuery.golf.prepare(e);
              var ret = orig.call(jQuery(this), e);
              jQuery(e.parent()).each(function() {
                jQuery(this).removeData("_golf_prepared");
              });
              if (a instanceof Component && a.onAppend)
                a.onAppend();
              return jQuery(this);
            }; 
        })(jQuery.fn[v]);
    });

    jQuery.fn.href = (function() {
        var uri2;
        return function(uri) {
          var uri1  = jQuery.golf.parseUri(uri);

          if (!uri2)
            uri2 = jQuery.golf.parseUri(servletUrl);

          if (uri1.protocol == uri2.protocol 
              && uri1.authority == uri2.authority
              && uri1.directory.substr(0, uri2.directory.length) 
                  == uri2.directory) {
            if (uri1.queryKey.path) {
              if (cloudfrontDomain.length)
                uri = cloudfrontDomain[0]+uri.queryKey.path;
            } else if (uri1.anchor) {
              if (serverside)
                uri = servletUrl + uri1.anchor;
            }
          }
          this.attr("href", uri);
        }; 
    })();
})();

// Static jQuery methods

jQuery.Import = function(name) {
  var ret="", obj, basename, dirname, i;

  basename = name.replace(/^.*\./, "");
  dirname  = name.replace(/\.[^.]*$/, "");

  if (basename == "*") {
    obj = eval(dirname);
    for (i in obj)
      ret += "var "+i+" = "+dirname+"['"+i+"'];";
  } else {
    ret = "var "+basename+" = "+name+";";
  }

  return ret;
};

jQuery.require = function(plugin) {
  var js = jQuery.golf.plugins[plugin].js;
  var argv = Array.prototype.slice.call(arguments, 1);
  if (js.length > 10)
    jQuery.golf.doCall(window, jQuery, argv, js);
};

// main jQ golf object

jQuery.golf = {

  defaultRoute: "/home/",
  
  onRouteError: undefined,

  events: [],

  htmlEncode: function(text) {
    return text.replace(/&/g,   "&amp;")
               .replace(/</g,   "&lt;")
               .replace(/>/g,   "&gt;")
               .replace(/"/g,   "&quot;");
  },

  /* parseUri is based on work (c) 2007 Steven Levithan <stevenlevithan.com> */

  parseUri: (function() {
    var o = {
      strictMode: true,
      key: ["source","protocol","authority","userInfo","user","password",
            "host","port","relative","path","directory","file","query","anchor"],
      q:   {
        name:   "queryKey",
        parser: /(?:^|&)([^&=]*)=?([^&]*)/g
      },
      parser: {
        strict: /^(?:([^:\/?#]+):)?(?:\/\/((?:(([^:@]*):?([^:@]*))?@)?([^:\/?#]*)(?::(\d*))?))?((((?:[^?#\/]*\/)*)([^?#]*))(?:\?([^#]*))?(?:#(.*))?)/,
        loose:  /^(?:(?![^:@]+:[^:@\/]*@)([^:\/?#.]+):)?(?:\/\/)?((?:(([^:@]*):?([^:@]*))?@)?([^:\/?#]*)(?::(\d*))?)(((\/(?:[^?#](?![^?#\/]*\.[^?#\/.]+(?:[?#]|$)))*\/?)?([^?#\/]*))(?:\?([^#]*))?(?:#(.*))?)/
      }
    };
    return function(str) {
      var m   = o.parser[o.strictMode ? "strict" : "loose"].exec(str),
          uri = {},
          i   = 14;

      while (i--) uri[o.key[i]] = m[i] || "";

      uri[o.q.name] = {};
      uri[o.key[12]].replace(o.q.parser, function ($0, $1, $2) {
        if ($1) uri[o.q.name][$1] = $2;
      });

      return uri;
    };
  })(),

  makePkg: function(pkg, obj) {
    if (!obj)
      obj = Component;

    if (!pkg || !pkg.length)
      return obj;

    var r = /^([^.]+)((\.)([^.]+.*))?$/;
    var m = pkg.match(r);

    if (!m)
      throw "bad package: '"+pkg+"'";

    if (!obj[m[1]])
      obj[m[1]] = {};

    return jQuery.golf.makePkg(m[4], obj[m[1]]);
  },

  setupComponents: function() {
    var cmp, name, i, m, pkg, scripts=[];

    for (name in jQuery.golf.components) {
      cmp = jQuery.golf.components[name];
      if (jQuery("head style[title='"+name+"']").size() == 0) {
        // add css to <head>
        if (cmp.css.replace(/^\s+|\s+$/g, '').length > 3)
          jQuery("head").append(
              "<style type='text/css' title='"+name+"'>"+cmp.css+"</style>");
        cmp.css = false;
      }

      if (!(m = name.match(/^(.*)\.([^.]+)$/)))
        throw "bad component name: '"+name+"'";

      pkg = jQuery.golf.makePkg(m[1]);
      pkg[m[2]] = jQuery.golf.componentConstructor(name);
    }

    for (name in jQuery.golf.models) {
      mdl = jQuery.golf.models[name];
      if (!(m = name.match(/^(.*)\.([^.]+)$/)))
        throw "bad model name: '"+name+"'";

      pkg = jQuery.golf.makePkg(m[1], Model);
      pkg[m[2]] = jQuery.golf.modelConstructor(name);
    }

    for (name in jQuery.golf.scripts)
      scripts.push(name);

    // sort scripts by name
    scripts = scripts.sort();

    for (i=0, m=scripts.length; i<m; i++)
      jQuery.globalEval(jQuery.golf.scripts[scripts[i]].js);
  },

  doCall: function(obj, $, argv, js) {
    if (js.length > 10) {
      var f;
      eval("f = "+js);
      f.apply(obj, argv);
    }
  },
    
  onLoad: function() {
    if (serverside)
      $("noscript").remove();

    if (urlHash && !location.hash)
      location.href = servletUrl + "#" + urlHash;

    jQuery.address.change(function(evnt) {
        jQuery.golf.onHistoryChange(evnt.value);
    });
  },

  onHistoryChange: (function() {
    var lastHash = "";
    return function(hash, b) {
      if (hash == "/") {
        jQuery.address.value(String(jQuery.golf.defaultRoute));
        return;
      }

      if (hash && hash != lastHash) {
        lastHash = hash;
        hash = hash.replace(/^\/+/, "/");
        jQuery.golf.route(hash, b);
        jQuery.golf.location = String(hash+"/").replace(/\/+$/, "/");
        window.location.hash = "#"+jQuery.golf.location;
      }
    };
  })(),

  route: function(hash, b) {
    var theName, theAction, i, x, pat, match;
    if (!hash)
      hash = String(jQuery.golf.defaultRoute+"/").replace(/\/+$/, "/");

    theName         = hash;
    theAction       = null;

    if (!b) b = jQuery("body > div.golfbody").eq(0);
    //b.empty();

    for (i in jQuery.golf.controller) {
      pat   = new RegExp(i);
      match = theName.match(pat);

      if (match) {
        theAction = jQuery.golf.controller[i];
        if (theAction(b, match)==false)
          break;
        theAction = null;
      }
    }
  },

  prepare: function(p) {
    jQuery("a", p.parent()).each(function() { 
        var jself = jQuery(this);
        if (jself.data("_golf_prepared"))
          return;
        jself.data("_golf_prepared", true);
        jself.href(this.href);
    });
    return p;
  },

  componentConstructor: function(name) {
    var result = function() {
      var argv = Array.prototype.slice.call(arguments);
      var obj  = this;

      var $ = function(selector) {
        var isHtml = /^[^<]*(<(.|\s)+>)[^>]*$/;

        // if it's not a selector then passthru to jQ
        if (typeof(selector) != "string" || selector.match(isHtml))
          return jQuery(selector);

        return jQuery(selector, obj._dom)
                  .not(jQuery(".component *", obj._dom))
                  .not(".component");
      };

      jQuery.extend($, jQuery);

      var cmp = jQuery.golf.components[name];
      
      $.component = cmp;

      $.require = function(plugin) {
        var js = jQuery.golf.plugins[plugin].js;
        var argv = Array.prototype.slice.call(arguments, 1);
        if (js.length > 10)
          jQuery.golf.doCall(obj, $, argv, js);
      }

      if (cmp) {
        obj._dom = jQuery(cmp.html);
        jQuery.golf.doCall(obj, $, argv, cmp.js);
      } else {
        throw "can't find component: "+name;
      }
    };
    result.prototype = new Component();
    return result;
  },

  modelConstructor: function(name) {
    var result = function() {
      var argv    = Array.prototype.slice.call(arguments);
      var obj     = this;
      var $       = {};
      var cmp     = jQuery.golf.models[name];
      
      $.component = cmp;

      if (cmp) {
        jQuery.golf.doCall(obj, $, argv, cmp.js);
      } else {
        throw "can't find model: "+name;
      }
    };
    result.prototype = new Model();
    return result;
  }
};

jQuery(jQuery.golf.onLoad);
