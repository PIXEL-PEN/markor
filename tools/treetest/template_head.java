import java.io.File;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Set;

/** Scaffolding around the VERBATIM method bodies lifted from FolderTreeFragment.java. */
public class TreeExpansionTest {

    // --- the fragment's state, same names -----------------------------------
    private final Set<String> _expanded = new HashSet<>();
    private String _seededMonth;
    private String _seededCategory;
    private String _pendingCategoryExpand;
    private File _root;
    private File _focus;

