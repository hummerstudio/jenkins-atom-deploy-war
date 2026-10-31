package com.jenkinschina.atom.deploywar;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import com.jenkinschina.atom.sdk.Glob;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deploys a WAR to a running servlet container through its management endpoint, then asks the container what it
 * actually has.
 *
 * <p>The plugin this replaces ({@code deploy}) drives Cargo from a Jenkins job: pick a container, paste its address,
 * give it credentials, upload the WAR. Cargo's Tomcat remote deployer then issues the Tomcat Manager "text" commands
 * ({@code deploy?path=…&update=true}, {@code list}, {@code undeploy}). That management protocol is the reusable part,
 * and it is HTTP, so this atom talks it directly — no Cargo, no container installation on the agent, no shelling out.
 *
 * <p>The reason this cannot just trust the HTTP status: Tomcat answers <b>200</b> for failures. A second deploy
 * without {@code update} comes back {@code 200} with {@code FAIL - Application already exists at path /x}; a corrupt
 * WAR comes back {@code 200} with a "context failed to start" message and the application <i>is</i> registered, in
 * state {@code stopped}. Both answers are also localised — on a Chinese locale the failure text is
 * {@code 失败 - 应用程序已存在…} — so nothing here matches an English sentence. Success means: the reply marker is
 * {@code OK}, <b>and</b> a readback of {@code list} shows the context path in state {@code running}.
 */
public class DeployWarAtom implements AtomTask {

    private static final String DEFAULT_MANAGER_PATH = "/manager/text";
    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_.]*)}|\\$([A-Za-z_][A-Za-z0-9_]*)");

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        String rawUrl = context.getParam("url", "").trim();
        String managerPathParam = context.getParam("managerPath", "").trim();
        String warPattern = context.getParam("war", "**/*.war").trim();
        String contextPathParam = context.getParam("contextPath", "").trim();
        String username = context.getParam("username", "").trim();
        boolean update = context.getBooleanParam("update", true);
        int timeoutSeconds = Math.max(5, Math.min(context.getIntParam("timeoutSeconds", 120), 3600));

        String password = secret(context, "password");
        if (password.isEmpty()) {
            password = secret(context, "credentialsId");
        }

        if (rawUrl.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40001,
                    "url is required: the container's address, e.g. http://tomcat.internal:8080 (a full "
                            + "http://host:8080/manager/text URL is accepted too)");
        }
        if (warPattern.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40002, "war is required (a path or an Ant-style glob, e.g. **/*.war)");
        }
        if (username.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40003,
                    "username is required (the manager-script user of the container)");
        }
        if (password.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40004,
                    "password is required: bind a username/password credential, or set the password secret");
        }

        String url;
        String managerPath;
        try {
            url = expand(rawUrl, context);
            managerPath = managerPathParam.isEmpty() ? "" : expand(managerPathParam, context);
        } catch (IllegalArgumentException e) {
            return AtomResult.error(ErrorType.USER, 40005, e.getMessage());
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return AtomResult.error(ErrorType.USER, 40006, "url must start with http:// or https:// — got " + url);
        }

        // The plugin's "Tomcat URL" field is Cargo's manager URL, i.e. it usually already ends in /manager/text.
        // Both shapes are accepted: what is left over is treated as the manager path.
        String managerBase;
        try {
            managerBase = managerBase(url, managerPath);
        } catch (IllegalArgumentException e) {
            return AtomResult.error(ErrorType.USER, 40006, e.getMessage());
        }

        Path workspace = context.getWorkspace();
        List<Path> wars = Glob.find(workspace, warPattern).stream()
                .filter(Files::isRegularFile)
                .sorted()
                .toList();
        if (wars.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40007,
                    "no WAR/EAR matched '" + warPattern + "' under " + workspace);
        }

        String explicitContext = "";
        if (!contextPathParam.isEmpty()) {
            if (wars.size() > 1) {
                return AtomResult.error(ErrorType.USER, 40008, "contextPath was given but " + wars.size()
                        + " archives matched '" + warPattern + "': one context path cannot name them all");
            }
            try {
                explicitContext = normalizeContextPath(expand(contextPathParam, context));
            } catch (IllegalArgumentException e) {
                return AtomResult.error(ErrorType.USER, 40009, e.getMessage());
            }
        }

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        String authorization = "Basic " + Base64.getEncoder()
                .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));

        context.group("deploy " + wars.size() + " archive(s) to " + managerBase);
        List<String> contextPaths = new ArrayList<>();
        Map<String, String> states = new LinkedHashMap<>();

        for (Path war : wars) {
            String relative = workspace.relativize(war).toString().replace('\\', '/');
            String contextPath = explicitContext.isEmpty()
                    ? deriveContextPath(war.getFileName().toString())
                    : explicitContext;
            long size = Files.size(war);

            String deployUrl = managerBase + "/deploy?path=" + encodeQuery(contextPath)
                    + (update ? "&update=true" : "");
            context.log("deploying " + relative + " (" + size + " bytes) at context path '"
                    + displayContext(contextPath) + "'");

            HttpResponse<String> response;
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(deployUrl))
                        .timeout(Duration.ofSeconds(timeoutSeconds))
                        .header("Authorization", authorization)
                        .header("Content-Type", "application/octet-stream")
                        .PUT(HttpRequest.BodyPublishers.ofFile(war))
                        .build();
                response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (Exception e) {
                return AtomResult.error(ErrorType.ENVIRONMENT, 40010,
                        "cannot reach " + managerBase + " to deploy " + relative + ": " + oneLine(String.valueOf(e.getMessage())));
            }

            int status = response.statusCode();
            String body = response.body() == null ? "" : response.body();
            if (status == 401) {
                return AtomResult.error(ErrorType.USER, 40011, "the container rejected the credentials for "
                        + managerBase + " (HTTP 401): check username/password and that the user holds the "
                        + "'manager-script' role");
            }
            if (status == 403) {
                return AtomResult.error(ErrorType.USER, 40012, "authenticated but not allowed to deploy (HTTP 403) — "
                        + "the user needs the 'manager-script' role");
            }
            if (status == 404) {
                return AtomResult.error(ErrorType.USER, 40013, "no manager application at " + managerBase
                        + " (HTTP 404): the URL is wrong, or the manager webapp is not deployed / is at another path");
            }
            if (status >= 500) {
                return AtomResult.error(ErrorType.THIRD_PART, 40014, "the container failed while deploying "
                        + relative + " (HTTP " + status + "): " + firstLine(body));
            }
            if (status < 200 || status >= 300) {
                return AtomResult.error(ErrorType.THIRD_PART, 40015,
                        "unexpected HTTP " + status + " from " + deployUrl + ": " + firstLine(body));
            }
            // 200 is not success: Tomcat reports "FAIL - …" (localised) inside a 200.
            if (!isOkReply(body)) {
                String marker = marker(body);
                String message = firstLine(body);
                boolean exists = body.contains("already exists") || body.contains("已存在");
                boolean badPath = body.contains("Invalid context path") || body.contains("无效");
                ErrorType type = exists || badPath ? ErrorType.USER : ErrorType.THIRD_PART;
                int code = exists ? 40016 : badPath ? 40017 : 40018;
                String hint = exists
                        ? " — deploy with update=true to replace it"
                        : badPath ? " — a context path must start with '/' (or be empty for ROOT)" : "";
                return AtomResult.error(type, code,
                        "the container refused " + relative + " at '" + displayContext(contextPath) + "' ("
                                + marker + "): " + message + hint);
            }

            // Read back what the container now says it has. A 200 "OK" plus a missing or stopped application is the
            // shape of a deploy that did not happen, and it is the reason this check exists.
            HttpResponse<String> listing;
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(managerBase + "/list"))
                        .timeout(Duration.ofSeconds(30))
                        .header("Authorization", authorization)
                        .GET()
                        .build();
                listing = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (Exception e) {
                return AtomResult.error(ErrorType.THIRD_PART, 40019,
                        "deployed " + relative + " but cannot read the application list back: " + oneLine(e.getMessage()));
            }
            if (listing.statusCode() != 200) {
                return AtomResult.error(ErrorType.THIRD_PART, 40019,
                        "deployed " + relative + " but the manager answered HTTP " + listing.statusCode()
                                + " for /list, so the deploy cannot be verified");
            }
            Map<String, String> applications = parseList(listing.body());
            String lookup = contextPath.isEmpty() ? "/" : contextPath;
            String state = applications.get(lookup);
            if (state == null) {
                return AtomResult.error(ErrorType.THIRD_PART, 40020, "the container answered OK for " + relative
                        + " but its application list has no '" + lookup + "' — nothing was deployed (known: "
                        + String.join(", ", applications.keySet()) + ")");
            }
            if (!"running".equalsIgnoreCase(state)) {
                return AtomResult.error(ErrorType.THIRD_PART, 40021, "the container answered OK for " + relative
                        + " but '" + lookup + "' is in state '" + state + "', not running — the application did not "
                        + "start (a corrupt or unsupported WAR looks exactly like this)");
            }

            contextPaths.add(lookup);
            states.put(lookup, state);
            context.log("verified: " + lookup + " is " + state);
        }

        return AtomResult.success("deployed " + wars.size() + " archive(s) to " + managerBase + ": "
                        + String.join(", ", contextPaths))
                .data("deployed", String.valueOf(wars.size()))
                .data("contextPaths", String.join(",", contextPaths))
                .data("states", joinStates(states))
                .data("managerUrl", managerBase)
                .data("verified", "true");
    }

    // ------------------------------------------------------------------ helpers

    private static String secret(AtomContext context, String name) {
        String value = context.getSecret(name);
        return value == null ? "" : value.trim();
    }

    /**
     * Splits the address the user gave into the manager base. Three shapes come up in practice: the container root
     * ({@code http://host:8080}), the manager text API the plugin's field holds ({@code …/manager/text}) and the
     * manager webapp with the version suffix ({@code …/manager} + {@code /text} passed separately).
     */
    static String managerBase(String url, String managerPath) {
        String trimmed = url.replaceAll("/+$", "");
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("url is not a valid URI: " + url);
        }
        if (uri.getHost() == null) {
            throw new IllegalArgumentException("url has no host: " + url);
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        String base;
        if (path.contains("/manager")) {
            base = trimmed;
            if (!managerPath.isEmpty() && !filesUnder(base, managerPath)) {
                base = base.replaceAll("/+$", "") + (managerPath.startsWith("/") ? managerPath : "/" + managerPath);
            }
        } else {
            String suffix = managerPath.isEmpty() ? DEFAULT_MANAGER_PATH : managerPath;
            if (!suffix.startsWith("/")) {
                suffix = "/" + suffix;
            }
            base = trimmed + suffix;
        }
        return base.replaceAll("/+$", "");
    }

    private static boolean filesUnder(String base, String suffix) {
        return base.endsWith(suffix.startsWith("/") ? suffix : "/" + suffix);
    }

    /** {@code ""} means ROOT, which the manager displays as {@code /}. */
    static String normalizeContextPath(String contextPath) {
        String path = contextPath.trim();
        if (path.isEmpty() || path.equals("/") || path.equals("ROOT")) {
            return "";
        }
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("contextPath must start with '/' (or be empty for ROOT) — got "
                    + contextPath);
        }
        if (path.contains(" ") || path.contains("?") || path.contains("&") || path.contains("#")) {
            throw new IllegalArgumentException("contextPath contains a character a context path cannot hold: "
                    + contextPath);
        }
        return path.replaceAll("/+$", "");
    }

    /** Tomcat's own convention when no context path is configured: the archive name, ROOT.war being the root. */
    static String deriveContextPath(String fileName) {
        String name = fileName;
        for (String extension : List.of(".war", ".ear")) {
            if (name.toLowerCase().endsWith(extension)) {
                name = name.substring(0, name.length() - extension.length());
            }
        }
        if (name.equalsIgnoreCase("ROOT")) {
            return "";
        }
        return "/" + name;
    }

    /** First line's marker: {@code OK}, {@code FAIL}, {@code 失败} — whatever the container's locale says. */
    static String marker(String body) {
        String first = firstLine(body);
        int dash = first.indexOf(" - ");
        return (dash > 0 ? first.substring(0, dash) : first).trim();
    }

    static boolean isOkReply(String body) {
        return "OK".equalsIgnoreCase(marker(body));
    }

    /**
     * Reads Tomcat's {@code /list} answer into context path → state. The first line is the header (localised), the
     * rest are {@code /path:state:sessions:displayName}.
     */
    static Map<String, String> parseList(String body) {
        Map<String, String> applications = new LinkedHashMap<>();
        if (body == null) {
            return applications;
        }
        List<String> lines = body.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || !line.startsWith("/")) {
                continue;
            }
            String[] fields = line.split(":", 4);
            if (fields.length < 2) {
                continue;
            }
            applications.put(fields[0], fields[1]);
        }
        return applications;
    }

    static String firstLine(String body) {
        if (body == null) {
            return "";
        }
        return body.lines().map(String::trim).filter(s -> !s.isEmpty()).findFirst().orElse("");
    }

    private static String joinStates(Map<String, String> states) {
        List<String> pairs = new ArrayList<>();
        states.forEach((path, state) -> pairs.add(path + "=" + state));
        return String.join(",", pairs);
    }

    private static String displayContext(String contextPath) {
        return contextPath.isEmpty() ? "/" : contextPath;
    }

    /** Percent-encodes a query value but keeps '/' — the manager wants a path, not an escaped blob. */
    static String encodeQuery(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("%2F", "/");
    }

    private static String oneLine(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    /** Expands {@code ${NAME}} / {@code $NAME} from the build parameters; an unexpanded one fails the step. */
    static String expand(String value, AtomContext context) {
        Matcher matcher = PLACEHOLDER.matcher(value);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            String replacement = context.getParam(name);
            if (replacement == null || replacement.isEmpty()) {
                throw new IllegalArgumentException(value + " refers to ${" + name + "}, which is not set");
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        String expanded = out.toString();
        if (expanded.contains("$")) {
            throw new IllegalArgumentException(value + " still contains a '$' after expansion");
        }
        return expanded;
    }
}
