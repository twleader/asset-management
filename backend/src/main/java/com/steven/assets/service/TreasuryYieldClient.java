package com.steven.assets.service;

import com.steven.assets.dto.TreasuryYieldDto;

import java.util.List;

/** Outbound port for fetching complete/incomplete Treasury curve batches. */
public interface TreasuryYieldClient {

    List<TreasuryYieldDto.FetchBatch> fetch(int year);
}
