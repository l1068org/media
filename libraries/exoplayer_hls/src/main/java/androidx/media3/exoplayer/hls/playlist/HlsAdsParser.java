package androidx.media3.exoplayer.hls.playlist;

import android.text.TextUtils;
import androidx.media3.common.util.Log;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class HlsAdsParser {

  private static final String TAG = "HlsAdsParser";

  private static final String TAG_EXT_INF = "#EXTINF";
  private static final String TAG_END_LIST = "#EXT-X-ENDLIST";
  private static final String TAG_DISCONTINUITY = "#EXT-X-DISCONTINUITY";
  private static final String DEFAULT_GROUP_IDENTIFIER = "NO_PATH";

  private static final int REASONABLE_GROUP_LIMIT = 10;
  private static final int MIN_PREFIX_LENGTH_TO_TEST = 5;
  private static final int SEQUENCE_NUMBER_RESERVED_LENGTH = 4;
  private static final double MIN_MAJORITY_GROUP_RATIO = 0.85;

  public static String process(String m3u8) {
    Log.d(TAG, "Executing HlsAdsParser [Final Code-Style Optimized Version]...");
    if (TextUtils.isEmpty(m3u8) || !m3u8.contains(TAG_END_LIST)) {
      return m3u8;
    }
    String[] lines = m3u8.split("\\r?\\n");
    Set<String> adSegments = findAds(lines);
    if (adSegments.isEmpty()) {
      Log.d(TAG, "No ad segments detected. Returning original content.");
      return m3u8;
    }
    Log.d(TAG, "Detected " + adSegments.size() + " ad segments. Rebuilding playlist.");
    return rebuildM3u8(lines, adSegments);
  }

  private static Set<String> findAds(String[] lines) {
    Log.d(TAG, "===== Executing Primary Strategy: Filename/Path Analysis =====");
    List<String> allSegments = new ArrayList<>();
    for (String line : lines) {
      String trimmedLine = line.trim();
      if (!trimmedLine.startsWith("#") && trimmedLine.endsWith(".ts")) {
        allSegments.add(trimmedLine);
      }
    }
    Set<String> adsFromFilename = findAdsByFilename(allSegments);
    if (!adsFromFilename.isEmpty()) {
      Log.d(TAG, "Primary strategy successful. Using its result.");
      return adsFromFilename;
    }
    Log.d(TAG, "Primary strategy did not find ads. Falling back to Discontinuity Analysis.");
    return findAdsByDiscontinuity(lines);
  }

  private static Set<String> findAdsByDiscontinuity(String[] lines) {
    List<List<String>> blocks = getDiscontinuityBlocks(lines);
    if (blocks.size() < 2) {
      Log.d(TAG, "Discontinuity Analysis: Only " + blocks.size() + " block(s) found. Strategy inconclusive.");
      return new HashSet<>();
    }
    List<String> minorityBlock = null;
    for (List<String> block : blocks) {
      Log.d(TAG, "Discontinuity Analysis: Found a block with " + block.size() + " segments.");
      if (minorityBlock == null || block.size() < minorityBlock.size()) {
        minorityBlock = block;
      }
    }
    if (!minorityBlock.isEmpty()) {
      Log.d(TAG, "Discontinuity Analysis: Minority block with " + minorityBlock.size() + " segments identified as ads.");
      return new HashSet<>(minorityBlock);
    }
    return new HashSet<>();
  }

  private static List<List<String>> getDiscontinuityBlocks(String[] lines) {
    List<List<String>> blocks = new ArrayList<>();
    List<String> currentBlock = new ArrayList<>();
    for (String line : lines) {
      String trimmedLine = line.trim();
      if (trimmedLine.equals(TAG_DISCONTINUITY)) {
        if (!currentBlock.isEmpty()) {
          blocks.add(currentBlock);
        }
        currentBlock = new ArrayList<>();
      } else if (!trimmedLine.startsWith("#") && trimmedLine.endsWith(".ts")) {
        currentBlock.add(trimmedLine);
      }
    }
    if (!currentBlock.isEmpty()) {
      blocks.add(currentBlock);
    }
    return blocks;
  }

  private static Set<String> findAdsByFilename(List<String> allSegments) {
    if (allSegments.size() < 2) {
      return new HashSet<>();
    }
    Map<String, List<String>> structuralGroups = new HashMap<>();
    for (String segment : allSegments) {
      String identifier = getStructuralIdentifier(segment);
      if (!structuralGroups.containsKey(identifier)) {
        structuralGroups.put(identifier, new ArrayList<>());
      }
      structuralGroups.get(identifier).add(segment);
    }
    if (structuralGroups.size() > 1) {
      return findMinorityGroup(structuralGroups);
    }
    return findAdsByPrefixAnalysis(allSegments);
  }

  private static String getStructuralIdentifier(String segmentUrl) {
    int lastSlashIndex = segmentUrl.lastIndexOf('/');
    return lastSlashIndex != -1 ? segmentUrl.substring(0, lastSlashIndex) : DEFAULT_GROUP_IDENTIFIER;
  }

  private static Set<String> findMinorityGroup(Map<String, List<String>> groups) {
    Map.Entry<String, List<String>> minEntry = null;
    for (Map.Entry<String, List<String>> entry : groups.entrySet()) {
      if (minEntry == null || entry.getValue().size() < minEntry.getValue().size()) {
        minEntry = entry;
      }
    }
    if (minEntry != null && groups.size() > 1) {
      return new HashSet<>(minEntry.getValue());
    }
    return new HashSet<>();
  }

  private static Set<String> findAdsByPrefixAnalysis(List<String> segments) {
    int optimalPrefixLength = findOptimalPrefixLength(segments);
    if (optimalPrefixLength == -1) {
      return new HashSet<>();
    }
    Map<String, Integer> identifierCounts = groupSegmentsByIdentifier(segments, optimalPrefixLength);
    if (identifierCounts.size() <= 1 || identifierCounts.size() > REASONABLE_GROUP_LIMIT) {
      return new HashSet<>();
    }
    Map.Entry<String, Integer> maxEntry = null;
    for (Map.Entry<String, Integer> entry : identifierCounts.entrySet()) {
      if (maxEntry == null || entry.getValue().compareTo(maxEntry.getValue()) > 0) {
        maxEntry = entry;
      }
    }
    String mainContentIdentifier = (maxEntry != null) ? maxEntry.getKey() : "";
    Set<String> adSegments = new HashSet<>();
    for (String segment : segments) {
      if (!getPrefixIdentifier(segment, optimalPrefixLength).equals(mainContentIdentifier)) {
        adSegments.add(segment);
      }
    }
    return adSegments;
  }

  private static int findOptimalPrefixLength(List<String> segments) {
    if (segments.size() < 2) {
      return -1;
    }
    int shortestSegmentLength = Integer.MAX_VALUE;
    for (String segment : segments) {
      if (segment.length() < shortestSegmentLength) {
        shortestSegmentLength = segment.length();
      }
    }
    int bestLength = -1;
    double highestScore = 0.0;
    for (int length = MIN_PREFIX_LENGTH_TO_TEST; length < shortestSegmentLength - SEQUENCE_NUMBER_RESERVED_LENGTH; length++) {
      Map<String, Integer> groups = groupSegmentsByIdentifier(segments, length);
      if (groups.size() > 1 && groups.size() <= REASONABLE_GROUP_LIMIT) {
        int maxGroupSize = 0;
        for (Integer count : groups.values()) {
          if (count > maxGroupSize) {
            maxGroupSize = count;
          }
        }
        double score = (double) maxGroupSize / segments.size();
        if (score > highestScore && score >= MIN_MAJORITY_GROUP_RATIO) {
          highestScore = score;
          bestLength = length;
        }
      }
    }
    return bestLength;
  }

  private static Map<String, Integer> groupSegmentsByIdentifier(List<String> allSegments, int prefixLength) {
    Map<String, Integer> identifierCounts = new HashMap<>();
    for (String segment : allSegments) {
      String identifier = getPrefixIdentifier(segment, prefixLength);
      Integer count = identifierCounts.get(identifier);
      identifierCounts.put(identifier, (count == null) ? 1 : count + 1);
    }
    return identifierCounts;
  }

  private static String getPrefixIdentifier(String segmentUrl, int prefixLength) {
    return segmentUrl.length() > prefixLength ? segmentUrl.substring(0, prefixLength) : segmentUrl;
  }

  private static String rebuildM3u8(String[] lines, Set<String> adSegments) {
    StringBuilder builder = new StringBuilder();
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i].trim();
      if (line.isEmpty()) {
        continue;
      }
      if (line.startsWith(TAG_EXT_INF)) {
        if (i + 1 < lines.length) {
          String nextLine = lines[i + 1].trim();
          if (adSegments.contains(nextLine)) {
            i++;
            continue;
          }
        }
      } else if (adSegments.contains(line)) {
        continue;
      }
      builder.append(line).append("\n");
    }
    return builder.toString();
  }
}