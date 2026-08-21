package com.xianda.freshdelivery.dto;

import java.util.List;

public record StockOverviewExportDto(
        String date,
        String filename,
        List<List<String>> productSheet,
        List<List<String>> specSheet,
        List<List<String>> orderSheet
) {
}
