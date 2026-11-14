package com.steven.assets.service.srpp.settings;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;

/** Only the configured Compose-internal calculator; no public data or broker requests. */
@Component
public class SrppSettingsHttpWorker implements SrppSettingsWorkerPort {
    private final HttpClient client;
    private final URI base;
    public SrppSettingsHttpWorker(@Value("${srpp.settings.worker-url:http://srpp-simulator:8080}") String url) {
        base=URI.create(url);
        client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public JsonNode catalog(){return call("catalog",null);}
    public JsonNode validate(JsonNode request){return call("validate",request);}
    public JsonNode simulate(JsonNode request){return call("simulate",request);}
    private JsonNode call(String action,JsonNode body) {
        try {
            HttpRequest.Builder req=HttpRequest.newBuilder(base.resolve("/internal/srpp/"+action))
                    .timeout(Duration.ofSeconds(action.equals("simulate")?120:10)).header("Accept","application/json");
            if(body==null)req.GET();else req.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(SettingsCanonicalJson.canonical(body)));
            HttpResponse<String> response=client.send(req.build(),HttpResponse.BodyHandlers.ofString());
            JsonNode value=SettingsCanonicalJson.parse(response.body());
            if(response.statusCode()!=200) {
                int status=response.statusCode();
                if(status==400||status==409||status==422)throw new SrppSettingsProblem(status,value.path("code").asText("CONFIG_INVALID"),value.path("message").asText("設定無法驗證"));
                throw new SrppSettingsProblem(503,"SIMULATOR_UNAVAILABLE","模擬服務暫時無法使用");
            }
            return value;
        } catch(SrppSettingsProblem e){throw e;}
        catch(java.net.http.HttpTimeoutException e){throw new SrppSettingsProblem(503,"SIMULATION_TIMEOUT","模擬逾時，請稍後重試");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new SrppSettingsProblem(503,"SIMULATION_INTERRUPTED","模擬已中斷");}
        catch(Exception e){throw new SrppSettingsProblem(503,"SIMULATOR_UNAVAILABLE","模擬服務暫時無法使用");}
    }
}
