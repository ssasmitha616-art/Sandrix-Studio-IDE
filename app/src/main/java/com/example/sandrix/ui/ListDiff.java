package com.example.sandrix.ui;

import java.util.List;

/**
 * Cheap comparison used by adapters to skip redundant full rebinds.
 * Items must be immutable (or replaced when they change) for this to be correct.
 */
final class ListDiff {
    private ListDiff() {}

    static boolean sameItems(List<?> a, List<?> b) {
        if (a == b) return true;
        if (a == null || b == null || a.size() != b.size()) return false;
        for (int i = 0, n = a.size(); i < n; i++) {
            if (a.get(i) != b.get(i)) return false;
        }
        return true;
    }
}
