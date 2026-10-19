package com.kopite.devspace.github.infrastructure;

import com.kopite.devspace.github.domain.GitHubException;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import tools.jackson.databind.json.JsonMapper;
import java.util.Map;

@Component
public class GitHubAppJwt {
    private final GitHubProperties settings;
    private final Clock clock;
    public GitHubAppJwt(GitHubProperties settings, Clock projectClock) { this.settings = settings; this.clock = projectClock; }

    public String issue() {
        if (!settings.isEnabled()) throw new GitHubException("GITHUB_NOT_CONFIGURED");
        try {
            String pem = Files.readString(Path.of(settings.getPrivateKeyPath()), StandardCharsets.US_ASCII);
            byte[] key = Base64.getMimeDecoder().decode(pem.replaceAll("-----[^\\r\\n]+-----", ""));
            if (pem.contains("BEGIN RSA PRIVATE KEY")) key = pkcs8(key);
            var privateKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(key));
            long now = clock.instant().getEpochSecond();
            String payload = encode("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8)) + "."
                    + encode(JsonMapper.builder().build().writeValueAsBytes(Map.of("iat", now - 60, "exp", now + 540, "iss", settings.getClientId())));
            var signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey); signature.update(payload.getBytes(StandardCharsets.US_ASCII));
            return payload + "." + encode(signature.sign());
        } catch (Exception ex) { throw new GitHubException("GITHUB_CREDENTIALS_UNAVAILABLE"); }
    }

    // GitHub downloads PKCS#1; JCA consumes PKCS#8 PrivateKeyInfo.
    static byte[] pkcs8(byte[] pkcs1) {
        byte[] algorithm = java.util.HexFormat.of().parseHex("020100300d06092a864886f70d0101010500");
        byte[] octets = der(4, pkcs1);
        byte[] body = new byte[algorithm.length + octets.length];
        System.arraycopy(algorithm, 0, body, 0, algorithm.length);
        System.arraycopy(octets, 0, body, algorithm.length, octets.length);
        return der(48, body);
    }
    private static byte[] der(int tag, byte[] data) {
        int bytes = data.length < 128 ? 0 : (32 - Integer.numberOfLeadingZeros(data.length) + 7) / 8;
        byte[] out = new byte[2 + bytes + data.length]; out[0] = (byte) tag;
        out[1] = (byte) (bytes == 0 ? data.length : 128 | bytes);
        for (int i = 0; i < bytes; i++) out[2 + i] = (byte) (data.length >>> (8 * (bytes - i - 1)));
        System.arraycopy(data, 0, out, 2 + bytes, data.length); return out;
    }
    private static String encode(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
}
