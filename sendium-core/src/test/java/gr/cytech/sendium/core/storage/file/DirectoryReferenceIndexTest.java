package gr.cytech.sendium.core.storage.file;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DirectoryReferenceIndexTest {
    @TempDir
    Path root;

    @Test
    void splitsSharedPrefixesAndPagesInOrder() throws Exception {
        var index = new DirectoryReferenceIndex(root, 8, 4);
        var expected = new ArrayList<String>();
        for (int i = 80; i >= 0; i--) {
            String key = "%08x".formatted(i);
            expected.addFirst(key);
            index.add(key);
            index.add(key);
        }
        var actual = new ArrayList<String>();
        String cursor = null;
        while (true) {
            List<String> page = index.after(cursor, 3);
            if (page.isEmpty()) {
                break;
            }
            actual.addAll(page);
            cursor = page.getLast();
        }
        assertThat(actual).containsExactlyElementsOf(expected);
        assertThat(index.after("0000000a", 2)).containsExactly("0000000b", "0000000c");
        assertThat(index.after("ffffffff", 1)).isEmpty();
    }

    @Test
    void deletionAndAdmissionBetweenPagesPreserveCursorSemantics() throws Exception {
        var index = new DirectoryReferenceIndex(root, 4, 2);
        for (String key : List.of("0000", "0010", "0020", "ffff")) {
            index.add(key);
        }
        assertThat(index.after(null, 2)).containsExactly("0000", "0010");
        index.remove("0010");
        index.add("0001");
        index.add("0011");
        assertThat(index.after("0010", 10)).containsExactly("0011", "0020", "ffff");
        for (String key : List.of("0000", "0001", "0011", "0020", "ffff", "ffff")) {
            index.remove(key);
        }
        assertThat(index.after(null, 1)).isEmpty();
        index.add("1234");
        assertThat(index.after(null, 1)).containsExactly("1234");
    }

    @Test
    void boundsDirectoryReadsAndRejectsInvalidArguments() throws Exception {
        var index = new DirectoryReferenceIndex(root, 4, 2);
        assertThatThrownBy(() -> index.after(null, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> index.add("../x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> index.after("bad", 1)).isInstanceOf(IllegalArgumentException.class);
        for (String key : List.of("0000", "0001", "0002")) {
            java.nio.file.Files.createFile(root.resolve(key));
        }
        assertThatThrownBy(() -> index.after(null, 1)).hasMessage("pendingIndexTraversal|error|reason:directoryBoundExceeded");
    }
}
