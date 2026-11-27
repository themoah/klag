import io.vertx.core.json.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.sasl.Sasl;
import software.amazon.msk.auth.iam.IAMClientCallbackHandler;
import software.amazon.msk.auth.iam.IAMLoginModule;

public class MskIamSaslSmokeTest {
  public static void main(String[] args) throws Exception {
    // Use local signing credentials so the test can run without AWS or Kafka.
    System.setProperty("aws.accessKeyId", "klag-smoke-test-key");
    System.setProperty("aws.secretAccessKey", "klag-smoke-test-secret");

    // Initializing the login module registers the SASL provider.
    Class.forName(IAMLoginModule.class.getName());
    var entry = new AppConfigurationEntry(
      IAMLoginModule.class.getName(), AppConfigurationEntry.LoginModuleControlFlag.REQUIRED, Map.of());
    var callback = new IAMClientCallbackHandler();
    try {
      callback.configure(Map.of(), IAMLoginModule.MECHANISM, List.of(entry));
      var client = Sasl.createSaslClient(
        new String[] {IAMLoginModule.MECHANISM}, null, "kafka",
        "b-1.example.kafka.us-east-1.amazonaws.com", Map.of(), callback);
      if (client == null) {
        throw new IllegalStateException("AWS_MSK_IAM SASL provider is missing");
      }
      try {
        byte[] response = client.evaluateChallenge(new byte[0]);
        var payload = new JsonObject(new String(response, StandardCharsets.UTF_8));
        if (!"kafka-cluster:Connect".equals(payload.getString("action"))
            || !payload.getString("x-amz-credential", "").startsWith("klag-smoke-test-key/")
            || payload.getString("x-amz-signature", "").isBlank()) {
          throw new IllegalStateException("Expected an MSK IAM response signed with test credentials");
        }
      } finally {
        client.dispose();
      }
    } finally {
      callback.close();
    }
  }
}
