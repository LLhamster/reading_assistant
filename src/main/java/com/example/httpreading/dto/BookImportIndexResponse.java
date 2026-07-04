package com.example.httpreading.dto;

import java.util.List;
import java.util.Map;

public record BookImportIndexResponse(Map<String, Long> hashes,
                                      int indexedCount,
                                      int missingSourceCount,
                                      int duplicateContentCount,
                                      List<Long> missingSourceBookIds) {
}
