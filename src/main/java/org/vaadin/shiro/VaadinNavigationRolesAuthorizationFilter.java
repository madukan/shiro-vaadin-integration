package org.vaadin.shiro;

import com.vaadin.flow.shared.ApplicationConstants;
import com.vaadin.flow.shared.JsonConstants;
import elemental.json.JsonArray;
import elemental.json.JsonObject;
import elemental.json.JsonValue;
import elemental.json.impl.JsonUtil;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.web.filter.PathConfigProcessor;
import org.apache.shiro.web.servlet.AdviceFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static org.apache.shiro.util.StringUtils.split;

public class VaadinNavigationRolesAuthorizationFilter extends AdviceFilter implements PathConfigProcessor {

    private static transient final Logger log = LoggerFactory.getLogger(VaadinNavigationRolesAuthorizationFilter.class);

    protected Map<String, List<String>> rolesByLocation = new LinkedHashMap<>();

    private String loginUrl;

    private String redirectJavaScript;

    @Override
    public Filter processPathConfig(String path, String config) {
        log.trace("Adding path: " + path);

        List<String> roles = new ArrayList<>();
        if (config != null) {
            String[] split = split(config);
            roles = Arrays.asList(split);
        }

        this.rolesByLocation.put(path, roles);
        return this;
    }

    public String getLoginUrl() {
        return loginUrl;
    }

    public void setLoginUrl(String loginUrl) {
        this.loginUrl = loginUrl;
    }

    public String getRedirectJavaScript() {
        return redirectJavaScript;
    }

    public void setRedirectJavaScript(String redirectJavaScript) {
        this.redirectJavaScript = redirectJavaScript;
    }

    @Override
    protected boolean preHandle(ServletRequest request, ServletResponse response) throws IOException {
        HttpServletRequest httpServletRequest = (HttpServletRequest) request;
        HttpServletResponse httpServletResponse = (HttpServletResponse) response;
        String uri = httpServletRequest.getRequestURI();

        if ("/".equals(uri)) {
            return handleFromBody(uri, httpServletRequest, httpServletResponse);
        } else {
            return handleFromUri(uri, httpServletResponse);
        }
    }

    @Override
    protected void executeChain(ServletRequest request, ServletResponse response, FilterChain chain) throws Exception {
        HttpServletResponse httpServletResponse = (HttpServletResponse) response;
        CustomServletResponseWrapper wrappedResponse = new CustomServletResponseWrapper(httpServletResponse);
        super.executeChain(request, wrappedResponse, chain);

        if (wrappedResponse.getContentType().equals(JsonConstants.JSON_CONTENT_TYPE)) {
            String output = wrappedResponse.getBranch().toString();
            wrappedResponse.getBranch().close();

            if (output.startsWith("for(;;);")) {
                output = output.substring("for(;;);".length());
                JsonArray json = JsonUtil.parse(output);
                JsonArray execute = json.getObject(0).getArray(JsonConstants.UIDL_KEY_EXECUTE);

                if (execute != null) {
                    for (int i = 0; i < execute.length(); i++) {
                        JsonArray array = execute.getArray(i);
                        if (array.length() == 3 && array.getString(2).startsWith("history.pushState") && array.getString(1) != null && !array.getString(1).isEmpty()) {
                            String location = "/" + array.getString(1);
                            if (!authorized(location)) {
                                String content = getRedirectToLoginResponse();
                                response.getWriter().write(content);
                                response.setContentLength(content.length());
                                return;
                            }
                        }
                    }
                }
            }
        }

        response.getWriter().write(wrappedResponse.getMaster().toString());
        wrappedResponse.getMaster().close();
    }

    protected boolean handleFromBody(String uri, HttpServletRequest request, HttpServletResponse response) throws IOException {
        String locationFromBody = getLocationFromBody(request);
        String location = locationFromBody != null ? locationFromBody : uri;
        boolean authorized = authorized(location);

        if (!authorized) {
            response.getWriter().write(getRedirectToLoginResponse());
        }

        return authorized;
    }

    protected boolean handleFromUri(String location, HttpServletResponse response) {
        boolean authorized = authorized(location);

        if (!authorized) {
            response.setStatus(HttpServletResponse.SC_FOUND);
            response.setHeader("Location", loginUrl);
        }

        return authorized;
    }

    protected String getLocationFromBody(ServletRequest request) throws IOException {
        String body = request.getReader().lines().collect(Collectors.joining());
        if (!body.isEmpty()) {
            JsonObject json = JsonUtil.parse(body);
            JsonArray rpc = json.getArray(ApplicationConstants.RPC_INVOCATIONS);
            if (rpc != null) {
                JsonObject value = rpc.get(0);
                JsonValue type = value.get(JsonConstants.RPC_TYPE);
                if (JsonConstants.RPC_TYPE_NAVIGATION.equals(type.asString())) {
                    return "/" + value.getString(JsonConstants.RPC_NAVIGATION_LOCATION);
                }
            }
        }
        return null;
    }

    protected boolean authorized(String location) {
        List<String> roles = rolesByLocation.get(location);

        if (roles != null && !roles.isEmpty()) {
            Subject subject = SecurityUtils.getSubject();
            for (String role : roles) {
                if (subject.hasRole(role)) {
                    log.trace("Location: \"" + location + "\" (match)");
                    return true;
                }
            }
            log.trace("Location: \"" + location + "\" (doesn't match)");
            return false;
        }

        log.trace("Location: \"" + location + "\" (ignored)");
        return true;
    }

    private String getRedirectToLoginResponse() {
        String script = getRedirectJavaScript();
        if (script == null) {
            script = "location='" + loginUrl + "'";
        }
        return "for(;;);[{\"execute\":[[\"" + script + "\"]]}]";
    }

}
