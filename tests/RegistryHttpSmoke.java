package app.birch.debian;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.util.*;
import javax.net.ssl.HttpsURLConnection;

/** A process-local HTTPS transport for the real catalog/download code. No DNS, TLS or sockets. */
public final class RegistryHttpSmoke {
    private static final String REGISTRY = "https://registry-1.docker.io/v2/library/debian/";
    private static final String HUB = "https://hub.docker.com/v2/namespaces/library/repositories/debian/tags?page_size=60&name=";
    private static final String AUTH = "https://auth.docker.io/token?service=registry.docker.io&scope=repository:library/debian:pull";
    private static final String RELEASE = "https://deb.debian.org/debian/dists/";
    private static final ArrayDeque<Reply> replies = new ArrayDeque<>();
    private static int open;

    private static void check(boolean condition, String name) { if (!condition) throw new AssertionError(name); }
    private static String digest(byte[] bytes) throws Exception {
        byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
        return String.format(Locale.ROOT, "%064x", new java.math.BigInteger(1, hash));
    }
    private static final class Reply {
        final String address, bearer, location;
        final int code;
        final byte[] body;
        Reply(String address, String bearer, int code, String location, byte[] body) {
            this.address = address; this.bearer = bearer; this.code = code; this.location = location; this.body = body;
        }
    }
    private static void ok(String address, String bearer, String text) {
        replies.add(new Reply(address, bearer, 200, null, text.getBytes(StandardCharsets.UTF_8)));
    }
    private static void redirect(String address, String bearer, String next) {
        replies.add(new Reply(address, bearer, 307, next, new byte[0]));
    }
    private static class Transport extends URLStreamHandler {
        @Override protected URLConnection openConnection(URL url) {
            Reply reply = replies.remove();
            check(reply.address.equals(url.toString()), "unexpected URL: " + url);
            open++;
            return new HttpsURLConnection(url) {
                public int getResponseCode() {
                    check(Objects.equals(reply.bearer, getRequestProperty("Authorization")), "wrong credential scope: " + url);
                    check(!getInstanceFollowRedirects() && !getUseCaches(), "manual redirect and uncached catalog");
                    check(getConnectTimeout() > 0 && getReadTimeout() > 0, "bounded timeouts");
                    return reply.code;
                }
                public InputStream getInputStream() { return new ByteArrayInputStream(reply.body); }
                public String getHeaderField(String name) { return reply.location; }
                public void disconnect() { open--; }
                public void connect() { throw new AssertionError("no network"); }
                public boolean usingProxy() { return false; }
                public String getCipherSuite() { return "test"; }
                public Certificate[] getLocalCertificates() { return null; }
                public Certificate[] getServerCertificates() { return null; }
            };
        }
    }
    private static void expectCatalogError(String message) throws Exception {
        try {
            DebianRegistry.INSTANCE.catalog(DebianRegistry.Channel.STABLE);
            throw new AssertionError("catalog should have failed");
        } catch (Exception ex) {
            check(ex instanceof IOException, ex.toString());
            check(ex.getMessage().contains(message), ex.toString());
            check(open == 0 && replies.isEmpty(), "connections released on failure");
        }
    }
    public static void main(String[] args) throws Exception {
        URL.setURLStreamHandlerFactory(protocol -> {
            if (protocol.equals("https") || protocol.equals("http")) return new Transport();
            return null;
        });
        String blob = "hello rootfs";
        String digest = digest(blob.getBytes(StandardCharsets.UTF_8));
        String manifest = "{\"schemaVersion\":2,\"layers\":[{\"mediaType\":\"application/vnd.oci.image.layer.v1.tar+gzip\",\"size\":"
                + blob.length() + ",\"digest\":\"sha256:" + digest + "\"}]}";
        String manifestId = "sha256:" + digest(manifest.getBytes(StandardCharsets.UTF_8));
        String result = "{\"results\":[{\"name\":\"13.10-slim\",\"images\":[{\"os\":\"linux\",\"architecture\":\"arm64\",\"digest\":\"" + manifestId + "\"}]}]}";
        ok(RELEASE + "stable/Release", null, "Suite: stable\nVersion: 13.10\n");
        ok(HUB + "13.", null, result);
        DebianRegistry registry = DebianRegistry.INSTANCE;
        DebianRegistry.Version version = registry.catalog(DebianRegistry.Channel.STABLE).get(0);
        check(open == 0 && replies.isEmpty() && version.getTag().equals("13.10-slim"), "catalog does not download the image");
        String bearer = "Bearer fixture";
        ok(AUTH, null, "{\"token\":\"fixture\"}");
        ok(REGISTRY + "manifests/" + manifestId, bearer, manifest);
        redirect(REGISTRY + "blobs/sha256:" + digest, bearer, "/redirected");
        redirect("https://registry-1.docker.io/redirected", bearer, "https://cdn.invalid/layer");
        ok("https://cdn.invalid/layer", null, blob);
        ImageStore store = new ImageStore(new File(args[0]));
        ImageStore.Image image = registry.download(version, store, (n, total) -> check(n <= total, "progress bound"));
        check(open == 0 && replies.isEmpty() && image.equals(store.pending()), "verified download persisted");
        store.clear();
        String stable = RELEASE + "stable/Release";
        ok(RELEASE + "testing/Release", null, "Suite: testing\nCodename: forky\n");
        ok(HUB + "forky-", null, result.replace("13.10-slim", "forky-20261004-slim"));
        check(registry.catalog(DebianRegistry.Channel.TESTING).get(0).getLabel().equals("forky · 2026-10-04"), "testing catalog");
        redirect(stable, null, "http://cdn.invalid/plain"); expectCatalogError("不安全");
        redirect(stable, null, "https://test@cdn.invalid/path"); expectCatalogError("不安全");
        for (int i = 0; i < 6; i++) redirect(stable, null, stable);
        expectCatalogError("跳转次数过多");
        replies.add(new Reply(stable, null, 429, null, new byte[0])); expectCatalogError("HTTP 429");
        ok(stable, null, "Suite: stable\nVersion: 13\n");
        replies.add(new Reply(HUB + "13.", null, 200, null, new byte[2 * 1024 * 1024 + 1])); expectCatalogError("目录过大");
        System.out.println("Registry transport: branch catalogs, verified pull, credential scope, limits and cleanup: PASS");
    }
}
