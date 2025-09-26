package androidx.media3.exoplayer.hls.playlist;

import androidx.media3.common.util.Log;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class HlsAdsParser {

  private static final String TAG = HlsAdsParser.class.getSimpleName();

  private static final String TAG_MEDIA_DURATION = "#EXTINF";
  private static final String TAG_ENDLIST = "#EXT-X-ENDLIST";

  private static final int REASONABLE_GROUP_LIMIT = 10;
  private static final int MIN_PREFIX_LENGTH_TO_TEST = 5;
  private static final int SEQUENCE_NUMBER_RESERVED_LENGTH = 4;
  private static final double MIN_MAJORITY_GROUP_RATIO = 0.85;

  public static String process(String m3u8) {
    if (!m3u8.contains(TAG_ENDLIST)) {
      return m3u8;
    }
    Set<String> adSegments = findAdsByFilename(m3u8);
    if (adSegments.isEmpty()) {
      Log.e(TAG, "未偵測到有效廣告，返回原始內容");
      return m3u8;
    }
    Log.e(TAG, "成功識別到 " + adSegments.size() + " 個廣告片段，開始重建 M3U8。");
    return rebuildM3u8(m3u8, adSegments);
  }

  private static Set<String> findAdsByFilename(String m3u8Content) {
    List<String> allSegments = new ArrayList<>();
    String[] lines = m3u8Content.split("\\r?\\n");
    for (String line : lines) {
      String trimmedLine = line.trim();
      if (!trimmedLine.startsWith("#") && trimmedLine.endsWith(".ts")) {
        allSegments.add(trimmedLine);
      }
    }
    if (allSegments.isEmpty()) {
      Log.e(TAG, "M3U8 中未找到任何 .ts 片段。");
      return new HashSet<>();
    }
    int optimalPrefixLength = findOptimalPrefixLength(allSegments);
    if (optimalPrefixLength == -1) {
      Log.e(TAG, "檔名分析：未找到一個佔據絕對多數的內容群組，認定沒有廣告。");
      return new HashSet<>();
    }
    Map<String, Integer> identifierCounts = groupSegmentsByIdentifier(allSegments, optimalPrefixLength);
    if (identifierCounts.size() <= 1 || identifierCounts.size() > REASONABLE_GROUP_LIMIT) {
      return new HashSet<>();
    }
    Log.e(TAG, "檔名分析：自動檢測到最佳長度為 " + optimalPrefixLength + "，共分出 " + identifierCounts.size() + " 組，判斷有效。");
    Map.Entry<String, Integer> maxEntry = null;
    for (Map.Entry<String, Integer> entry : identifierCounts.entrySet()) {
      if (maxEntry == null || entry.getValue().compareTo(maxEntry.getValue()) > 0) {
        maxEntry = entry;
      }
    }
    String mainContentIdentifier = (maxEntry != null) ? maxEntry.getKey() : "";
    Set<String> adSegments = new HashSet<>();
    for (String segment : allSegments) {
      if (!getSegmentIdentifier(segment, optimalPrefixLength).equals(mainContentIdentifier)) {
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
      String identifier = getSegmentIdentifier(segment, prefixLength);
      Integer count = identifierCounts.get(identifier);
      identifierCounts.put(identifier, (count == null) ? 1 : count + 1);
    }
    return identifierCounts;
  }

  private static String getSegmentIdentifier(String segmentUrl, int prefixLength) {
    int lastSlashIndex = segmentUrl.lastIndexOf('/');
    if (lastSlashIndex != -1) {
      return segmentUrl.substring(0, lastSlashIndex);
    } else {
      return segmentUrl.length() > prefixLength ? segmentUrl.substring(0, prefixLength) : segmentUrl;
    }
  }

  private static String rebuildM3u8(String m3u8, Set<String> adSegments) {
    StringBuilder builder = new StringBuilder();
    String[] lines = m3u8.split("\\r?\\n");
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i].trim();
      if (line.isEmpty()) {
        continue;
      }
      if (line.startsWith(TAG_MEDIA_DURATION)) {
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