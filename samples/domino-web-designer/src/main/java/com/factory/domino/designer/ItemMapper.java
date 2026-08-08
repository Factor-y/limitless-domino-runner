package com.factory.domino.designer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.hcl.domino.data.DominoDateTime;
import com.hcl.domino.data.Item;
import com.hcl.domino.data.ItemDataType;

/**
 * Converts a Domino {@link Item} into a JSON-friendly map.
 *
 * <p>Domino item values are not plain Java types — dates arrive as {@link DominoDateTime}, and
 * rich text is a stream of composite records rather than a value at all. This class normalises
 * what can be normalised and describes the rest, so that a document dump never fails because one
 * item happens to hold something unusual.
 */
final class ItemMapper {

  private ItemMapper() {
  }

  static Map<String, Object> describe(Item item) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("name", item.getName());

    ItemDataType type = null;
    try {
      type = item.getType();
      result.put("type", String.valueOf(type));
    } catch (RuntimeException e) {
      result.put("type", "UNKNOWN");
    }

    try {
      result.put("summary", item.isSummary());
      result.put("valueLength", item.getValueLength());
    } catch (RuntimeException e) {
      // Non-essential metadata; the value below is what matters.
    }

    // Rich text is deliberately not expanded: rendering composite data records faithfully is
    // a project of its own, and a document dump only needs to show that the item is there.
    if (type == ItemDataType.TYPE_COMPOSITE) {
      result.put("value", null);
      result.put("note", "rich text not expanded");
      return result;
    }

    try {
      List<Object> values = new ArrayList<>();
      for (Object value : item.getValue()) {
        values.add(normalize(value));
      }
      result.put("value", values);
    } catch (RuntimeException e) {
      result.put("value", null);
      result.put("note", "value could not be read: " + e.getClass().getSimpleName());
    }
    return result;
  }

  /** Maps a single Domino value to something Jackson can serialize meaningfully. */
  private static Object normalize(Object value) {
    if (value == null || value instanceof String || value instanceof Number
        || value instanceof Boolean) {
      return value;
    }
    if (value instanceof DominoDateTime dateTime) {
      try {
        // ISO-8601 keeps the value usable by the browser without further parsing.
        return dateTime.toOffsetDateTime().toString();
      } catch (RuntimeException e) {
        return dateTime.toString();
      }
    }
    if (value instanceof java.util.Collection<?> collection) {
      List<Object> normalized = new ArrayList<>();
      for (Object element : collection) {
        normalized.add(normalize(element));
      }
      return normalized;
    }
    return String.valueOf(value);
  }
}
