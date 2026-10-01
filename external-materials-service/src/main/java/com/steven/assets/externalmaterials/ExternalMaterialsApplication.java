package com.steven.assets.externalmaterials;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ExternalMaterialsApplication {
    public static void main(String[] args) {
        if (isHistoricalBackfillCommand(args)) {
            com.steven.assets.fubonhistoricalbackfill.FubonHistoricalBackfillApplication.main(args);
            return;
        }
        SpringApplication.run(ExternalMaterialsApplication.class, args);
    }

    static boolean isHistoricalBackfillCommand(String[] args) {
        return args != null && java.util.Arrays.stream(args)
                .anyMatch(argument -> argument.equals("--fubon-historical-backfill")
                        || argument.startsWith("--fubon-historical-backfill="));
    }
}
