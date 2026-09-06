package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.FubonMarketConfigState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.*; import java.util.function.Supplier; import java.util.regex.Pattern;

/** External-service-local renderer; it deliberately has no Python adapter dependency. */
@Component public class ApiErrorLogDiagnosticRenderer {
    private static final Pattern SENSITIVE_KEY_VALUE=Pattern.compile(
            "(?i)([\\\"']?(?:access[ _-]?token|refresh[ _-]?token|token|client[ _-]?secret|secret|password|authorization|api[ _-]?key|certificate|account|identity)[\\\"']?\\s*(?:=|:)\\s*)([\\\"']?)(?:bearer\\s+)?([^\\s,;\\\"'}&]+)([\\\"']?)");
    private static final Pattern BEARER_VALUE=Pattern.compile("(?i)\\bBearer\\s+[^\\s,;\\\"'}&]+");
    private final List<String> configuredSecrets;
    private final Supplier<List<String>> runtimeSecrets;
    public ApiErrorLogDiagnosticRenderer(String value){this(value,List::of);}
    /** The shared Fubon token is read lazily and locally only after FUBON_ENABLED permits it. */
    @Autowired public ApiErrorLogDiagnosticRenderer(@Value("${api-error-log.runtime-secrets:}") String value,
            @Value("${INTERNAL_TREASURY_TOKEN:}") String treasuryToken,
            @Value("${POSTGRES_PASSWORD:}") String postgresPassword,
            @Value("${finmind.token:${FINMIND_TOKEN:}}") String finmindToken,
            FubonMarketConfigState fubonConfigState){
        this(value,()->{FubonMarketConfigState.Snapshot snapshot=fubonConfigState.snapshot();List<String> values=new ArrayList<>(Arrays.asList(treasuryToken, postgresPassword, finmindToken));if(snapshot.reason()==null&&snapshot.token()!=null)values.add(snapshot.token());return values;});
    }
    private ApiErrorLogDiagnosticRenderer(String value,Supplier<List<String>> runtimeSecrets){configuredSecrets=split(value);this.runtimeSecrets=runtimeSecrets;}
    public String message(Throwable error){return sanitize(error==null?"":error.getMessage());}
    public String render(Throwable error){StringBuilder out=new StringBuilder();for(Throwable current=error;current!=null;current=current.getCause()){if(!out.isEmpty())out.append("Caused by: ");out.append(current.getClass().getName());String msg=sanitize(current.getMessage());if(!msg.isBlank())out.append(": ").append(msg);out.append('\n');for(StackTraceElement frame:current.getStackTrace())out.append("\tat ").append(frame).append('\n');}return out.toString();}
    public String sanitize(String value){String safe=value==null?"":value;for(String secret:allSecrets())safe=safe.replace(secret,"[REDACTED]");safe=SENSITIVE_KEY_VALUE.matcher(safe).replaceAll("$1$2[REDACTED]$4");return BEARER_VALUE.matcher(safe).replaceAll("Bearer [REDACTED]");}
    private List<String> allSecrets(){List<String> values=new ArrayList<>(configuredSecrets);try{values.addAll(runtimeSecrets.get());}catch(RuntimeException ignored){}return values.stream().filter(Objects::nonNull).map(String::trim).filter(value->!value.isEmpty()).distinct().sorted(Comparator.comparingInt(String::length).reversed()).toList();}
    private static List<String> split(String values){return Arrays.stream((values==null?"":values).split(",")).map(String::trim).filter(value->!value.isEmpty()).toList();}
}
