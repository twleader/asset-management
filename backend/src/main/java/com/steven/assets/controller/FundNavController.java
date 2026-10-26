package com.steven.assets.controller;

import com.steven.assets.dto.*;
import com.steven.assets.service.FundApplicationService;
import com.steven.assets.service.FundMaintenanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class FundNavController {
    private final FundApplicationService funds;
    private final FundMaintenanceService maintenance;

    @GetMapping("/funds")
    public List<FundDto> listFunds(@RequestParam(required = false) String date) {
        return funds.listFunds(date == null || date.isBlank() ? null : LocalDate.parse(date));
    }
    @PostMapping("/funds")
    public ResponseEntity<FundDto> createFund(@RequestBody CreateFundRequest req) {
        try { return ResponseEntity.ok(funds.createFund(req)); }
        catch (FundApplicationService.InvalidFundCodeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "fundCode 必填");
        } catch (FundApplicationService.FundAlreadyExistsException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }
    @PutMapping("/funds/{fundCode}")
    public ResponseEntity<FundDto> updateFund(@PathVariable String fundCode, @RequestBody UpdateFundRequest req) {
        try { return ResponseEntity.ok(funds.updateFund(fundCode, req)); }
        catch (FundApplicationService.FundNotFoundException e) { throw new ResponseStatusException(HttpStatus.NOT_FOUND); }
    }
    @PatchMapping("/funds/{fundCode}/active")
    public ResponseEntity<FundDto> setActive(@PathVariable String fundCode, @RequestBody Map<String, Boolean> body) {
        try { return ResponseEntity.ok(funds.setActive(fundCode, body.get("active"))); }
        catch (FundApplicationService.FundNotFoundException e) { throw new ResponseStatusException(HttpStatus.NOT_FOUND); }
    }
    @PostMapping(value = "/fund-nav/refresh", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> refreshNav() { return maintenance.refreshNav(); }
    @PostMapping(value = "/fund-nav/backfill", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> backfillNav(@RequestParam(defaultValue = "10") int years) { return maintenance.backfillNav(years); }
    @PostMapping(value = "/fund-dividend/backfill", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> backfillDividend(@RequestParam(defaultValue = "10") int years) { return maintenance.backfillDividend(years); }
    @PostMapping(value = "/fund-dividend/refresh", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> refreshDividend() { return maintenance.refreshDividend(); }
    @GetMapping("/fund-nav/latest")
    public Map<String, Object> latestNav(@RequestParam String fundCode) { return funds.latestNav(fundCode); }
}
