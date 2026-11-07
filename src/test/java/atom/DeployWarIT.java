package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.jenkinschina.atom.sdk.Json;
import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The deploy-war atom, started the way the runner starts it, against the Tomcat Manager fake in {@code tools/}.
 *
 * <p>These are the checks {@code tools/smoke.sh} made: the WAR bytes that arrive hash to the WAR on the workspace, the
 * context paths Tomcat derives, 200-is-not-success readback, every failure path with its own code, input refusals that
 * upload nothing, and the digest comparison that catches a short write.
 */
class DeployWarIT {

    private static final String PASSWORD = "s3cret-deploy";

    private Path module;
    private Path scratch;
    private final List<Process> servers = new ArrayList<>();
    private Process manager;
    private Path state;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        module = Path.of(System.getProperty("atom.module", System.getProperty("user.dir")));
        scratch = Path.of(System.getProperty("atom.scratch", "target"));
    }

    @AfterEach
    void tearDown() {
        servers.forEach(Process::destroy);
    }

    @Test
    @DisplayName("two WARs deploy at derived context paths, and the manager's digests match the workspace")
    void deploysTwoWarsWithDerivedPaths() throws Exception {
        start();
        AtomResult run = deploy(Atom.of()
                .fileBytes("target/app.war", war("alpha"))
                .fileBytes("target/shop-v2.war", war("beta"))
                .input("""
                        {"url": "%s", "username": "deployer", "war": "target/*.war"}
                        """.formatted(url())));

        run.assertOk();
        assertEquals("/app,/shop-v2", run.field("contextPaths"), run.file("output.json"));

        Map<String, Object> registry = registry();
        for (String[] pair : new String[][] {{"/app", "target/app.war"}, {"/shop-v2", "target/shop-v2.war"}}) {
            @SuppressWarnings("unchecked")
            Map<String, Object> app = (Map<String, Object>) registry.get(pair[0]);
            assertEquals(sha256(Files.readAllBytes(run.work().resolve(pair[1]))), app.get("sha256"),
                    "the deployed bytes are not the war from the workspace: " + app);
        }
    }

    @Test
    @DisplayName("an explicit context path is used, and a full /manager/text URL is accepted")
    void honoursAnExplicitContextPath() throws Exception {
        start();
        AtomResult run = deploy(Atom.of()
                .fileBytes("build/chosen.war", war("gamma"))
                .input("""
                        {"url": "%s/manager/text", "username": "deployer", "war": "build/chosen.war",
                         "contextPath": "/chosen"}
                        """.formatted(url())));

        run.assertOk();
        assertTrue(run.file("output.json").contains("/chosen=running"),
                "states missing /chosen=running: " + run.file("output.json"));
    }

    @Test
    @DisplayName("ROOT.war deploys at / (Tomcat's root convention)")
    void deploysRootWarAtRoot() throws Exception {
        start();
        AtomResult run = deploy(Atom.of()
                .fileBytes("ROOT.war", war("rootapp"))
                .input("""
                        {"url": "%s", "username": "deployer", "war": "ROOT.war"}
                        """.formatted(url())));

        run.assertOk();
        assertTrue(run.file("output.json").contains("/=running"), "ROOT.war did not land at /: " + run.file("output.json"));
    }

    @Test
    @DisplayName("input refusals upload nothing")
    void refusesBadInputBeforeUploading() throws Exception {
        start();
        AtomResult many = deploy(Atom.of()
                .fileBytes("a.war", war("one"))
                .fileBytes("b.war", war("two"))
                .input("""
                        {"url": "%s", "username": "deployer", "war": "*.war", "contextPath": "/one"}
                        """.formatted(url())));
        assertError(many, "40008", "one context path cannot name them all");

        AtomResult empty = deploy(Atom.of()
                .input("""
                        {"url": "%s", "username": "deployer", "war": "target/*.war"}
                        """.formatted(url())));
        assertError(empty, "40007", "no WAR/EAR matched");

        AtomResult slash = deploy(Atom.of()
                .fileBytes("a.war", war("one"))
                .input("""
                        {"url": "%s", "username": "deployer", "war": "a.war", "contextPath": "no-slash"}
                        """.formatted(url())));
        assertError(slash, "40009", "must start with");

        AtomResult scheme = deploy(Atom.of()
                .fileBytes("a.war", war("one"))
                .input("""
                        {"url": "127.0.0.1:%d", "username": "deployer", "war": "a.war"}
                        """.formatted(port)));
        assertError(scheme, "40006", "must start with http");

        assertTrue(!requests().contains("\"method\": \"PUT\""), "nothing may be uploaded when the input is refused:\n"
                + requests());
    }

    @Test
    @DisplayName("401 from the manager is a USER error naming the manager-script role")
    void reportsRejectedCredentials() throws Exception {
        start();
        AtomResult run = Atom.of()
                .fileBytes("a.war", war("one"))
                .input("""
                        {"url": "%s", "username": "deployer", "war": "a.war"}
                        """.formatted(url()))
                .file(".secrets.json", "{\"password\": \"wrong-password\"}")
                .run();

        assertError(run, "40011", "rejected the credentials");
        assertTrue(requests().contains("\"authorized\": false"), "the manager did not see the wrong password:\n"
                + requests());
    }

    @Test
    @DisplayName("--deny: 401 is still a USER error, not a container fault")
    void reportsDeniedCredentials() throws Exception {
        start("--deny");
        AtomResult run = deploy(Atom.of()
                .fileBytes("a.war", war("one"))
                .input("""
                        {"url": "%s", "username": "deployer", "war": "a.war"}
                        """.formatted(url())));

        assertError(run, "40011", "rejected the credentials");
    }

    @Test
    @DisplayName("a wrong manager path becomes a 404 hint about the URL")
    void reportsAWrongManagerPath() throws Exception {
        start("--manager-path", "/other/manager");
        AtomResult run = deploy(Atom.of()
                .fileBytes("a.war", war("one"))
                .input("""
                        {"url": "%s", "username": "deployer", "war": "a.war"}
                        """.formatted(url())));

        assertError(run, "40013", "no manager application at");
    }

    @Test
    @DisplayName("re-deploy with update=false: 200 plus localised '失败 - 已存在' becomes a USER error")
    void reportsAnExistingApplication() throws Exception {
        start();
        AtomResult first = deploy(Atom.of()
                .fileBytes("a.war", war("one"))
                .input("""
                        {"url": "%s", "username": "deployer", "war": "a.war", "update": "false"}
                        """.formatted(url())));
        first.assertOk();

        AtomResult second = deploy(Atom.of()
                .fileBytes("a.war", war("one"))
                .input("""
                        {"url": "%s", "username": "deployer", "war": "a.war", "update": "false"}
                        """.formatted(url())));

        assertError(second, "40016", "已存在");
    }

    @Test
    @DisplayName("a container 500 is a THIRD_PART error carrying the status")
    void reportsAContainerError() throws Exception {
        start("--status", "500");
        AtomResult run = deploy(Atom.of()
                .fileBytes("a.war", war("one"))
                .input("""
                        {"url": "%s", "username": "deployer", "war": "a.war"}
                        """.formatted(url())));

        assertError(run, "40014", "HTTP 500");
    }

    @Test
    @DisplayName("an OK reply with an empty application list fails the step")
    void failsWhenNothingWasRegistered() throws Exception {
        start("--no-register");
        AtomResult run = deploy(Atom.of()
                .fileBytes("a.war", war("one"))
                .input("""
                        {"url": "%s", "username": "deployer", "war": "a.war"}
                        """.formatted(url())));

        assertError(run, "40020", "nothing was deployed");
    }

    @Test
    @DisplayName("an OK reply with the application stopped fails the step (the corrupt-WAR shape)")
    void failsWhenTheApplicationIsStopped() throws Exception {
        start("--stopped");
        AtomResult run = deploy(Atom.of()
                .fileBytes("a.war", war("one"))
                .input("""
                        {"url": "%s", "username": "deployer", "war": "a.war"}
                        """.formatted(url())));

        assertError(run, "40021", "state 'stopped'");
    }

    @Test
    @DisplayName("a short-written WAR has a different digest from the file on the workspace")
    void catchesAShortWrite() throws Exception {
        start("--short-write");
        AtomResult run = deploy(Atom.of()
                .fileBytes("a.war", war("one"))
                .input("""
                        {"url": "%s", "username": "deployer", "war": "a.war"}
                        """.formatted(url())));

        run.assertOk();
        @SuppressWarnings("unchecked")
        Map<String, Object> app = (Map<String, Object>) registry().get("/a");
        assertNotEquals(sha256(Files.readAllBytes(run.work().resolve("a.war"))), app.get("sha256"),
                "the digest comparison did not notice the manager storing 100 bytes fewer: " + app);
    }

    @Test
    @DisplayName("an en-locale manager and a BUILD_NUMBER placeholder in the context path")
    void expandsPlaceholders() throws Exception {
        start("--locale", "en");
        AtomResult run = deploy(Atom.of()
                .fileBytes("a.war", war("one"))
                .input("""
                        {"url": "%s", "username": "deployer", "war": "a.war", "contextPath": "/app-${BUILD_NUMBER}",
                         "BUILD_NUMBER": "42"}
                        """.formatted(url())));

        run.assertOk();
        assertEquals("/app-42", run.field("contextPaths"), "placeholder not expanded: " + run.file("output.json"));
    }

    @Test
    @DisplayName("a real container, when one is offered through REAL_DEPLOY_URL")
    void deploysToARealContainer() throws Exception {
        String realUrl = System.getenv("REAL_DEPLOY_URL");
        assumeTrue(realUrl != null && !realUrl.isBlank(),
                "no real Tomcat was offered (REAL_DEPLOY_URL / REAL_DEPLOY_USER / REAL_DEPLOY_PASSWORD are unset)");

        AtomResult run = Atom.of()
                .fileBytes("dist/real.war", war("real"))
                .input("""
                        {"url": "%s", "username": "%s", "war": "dist/real.war", "contextPath": "/atom-smoke"}
                        """.formatted(realUrl, System.getenv("REAL_DEPLOY_USER")))
                .file(".secrets.json", "{\"password\": \"" + System.getenv("REAL_DEPLOY_PASSWORD") + "\"}")
                .run();

        run.assertOk();
        assertTrue(run.file("output.json").contains("/atom-smoke=running"),
                "real container: /atom-smoke not running: " + run.file("output.json"));
    }

    private AtomResult deploy(Atom atom) throws Exception {
        return atom.file(".secrets.json", "{\"password\": \"" + PASSWORD + "\"}").run();
    }

    private void assertError(AtomResult run, String code, String fragment) throws Exception {
        run.assertRefused();
        assertEquals(code, run.field("errorCode"), run.file("output.json"));
        assertTrue(run.file("output.json").contains(fragment),
                "expected error " + code + " (" + fragment + "), got: " + run.file("output.json"));
    }

    private Map<String, Object> registry() throws Exception {
        return Json.parseObject(Files.readString(state.resolve("apps.json")));
    }

    private String requests() throws Exception {
        Path file = state.resolve("requests.jsonl");
        return Files.isRegularFile(file) ? Files.readString(file) : "";
    }

    private String url() {
        return "http://127.0.0.1:" + port;
    }

    /** A WAR is a zip: the same two entries {@code jar cf} wrote in the smoke script. */
    private static byte[] war(String marker) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes)) {
            jar.putNextEntry(new ZipEntry("index.html"));
            jar.write(("<html><body>" + marker + "</body></html>").getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
            jar.putNextEntry(new ZipEntry("payload.txt"));
            jar.write(("w:" + marker).getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private void start(String... flags) throws Exception {
        state = Files.createTempDirectory(scratch, "manager");
        List<String> command = new ArrayList<>(List.of("python3",
                module.resolve("tools/fake_manager.py").toString(),
                "--state", state.toString(),
                "--port", "0"));
        command.addAll(List.of(flags));
        manager = new ProcessBuilder(command).redirectErrorStream(true).start();
        servers.add(manager);
        BufferedReader output = new BufferedReader(
                new InputStreamReader(manager.getInputStream(), StandardCharsets.UTF_8));
        String ready = output.readLine();
        if (ready == null || !ready.contains("\"port\"")) {
            throw new IllegalStateException("the fake manager did not start: " + ready);
        }
        port = ((Number) Json.parseObject(ready).get("port")).intValue();
    }
}
