// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.e4.ui.model.application.ui.MElementContainer;
import org.eclipse.e4.ui.model.application.ui.MGenericTile;
import org.eclipse.e4.ui.model.application.ui.MUIElement;
import org.eclipse.e4.ui.model.application.ui.advanced.MArea;
import org.eclipse.e4.ui.model.application.ui.basic.MCompositePart;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.e4.ui.model.application.ui.basic.MPartStack;
import org.eclipse.e4.ui.model.application.ui.basic.MStackElement;
import org.eclipse.e4.ui.workbench.IPresentationEngine;
import org.eclipse.e4.ui.workbench.modeling.EModelService;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.texteditor.ITextEditor;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.EditPath;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.WindowCommand;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.WindowLayout;

/**
 * Vim's windows as Eclipse editors: {@code :split} puts an editor into a new editor stack beside
 * the current one, {@code Ctrl-W l} activates the editor to the right, {@code :only} gathers every
 * editor into one stack again (docs/design/SPLITS.md). Neovim decides what a command means
 * ({@link WindowCommand}); this class does it to Eclipse's editor area.
 *
 * <h2>Background: the editor area as a model</h2>
 * Since Eclipse 4 the workbench is described by an application model ({@code EditorTabs} has the
 * basics). The editor area is an {@link MArea}, a sash container: its children are laid out side
 * by side or one above the other ({@link MGenericTile#isHorizontal()}), each child an editor stack
 * ({@link MPartStack}, a row of tabs) or another sash container. Dragging an editor's tab to the
 * edge of the area makes a new stack there; Eclipse's {@link EModelService} does the same in code:
 * {@code insert(newStack, beside, EModelService.RIGHT_OF, 0.5f)} puts a stack beside another,
 * {@code move(part, stack)} moves an editor into it. Each child's share of the space is its
 * {@code containerData}, a weight such as {@code "5000"}: set them, and the sashes move. An emptied
 * stack removes itself, so closing a split's last editor gives its space back.
 *
 * <p>So the area is a tree of rows and columns, as Vim's windows are, and each stack's selected
 * editor is one window. {@link WindowLayout} answers the questions on that tree (which window is to
 * the right, what the new sizes are); this class draws the tree from the model ({@link Picture})
 * and applies the answer.
 *
 * <p><b>Eclipse's own split halves</b> (<i>Window &gt; Editor &gt; Toggle Split Editor</i>, when the
 * hidden preference gives it back to Eclipse, or from a workspace that had them): the stack shows a
 * composite part with the two halves in a sash container of its own. They are windows here too, so
 * {@code Ctrl-W j}, {@code Ctrl-W +} and {@code :only} work on them; {@code Ctrl-W x} and
 * {@code Ctrl-W r}, which move editors between stacks, do not.
 *
 * <p>UI thread only.
 */
final class EditorSplits {

    private static final Logger LOG = Logger.getLogger(EditorSplits.class.getName());

    /** Window &gt; Editor &gt; Clone: a second editor on the same file, with the caret and scroll copied. */
    static final String CLONE_COMMAND = "org.eclipse.ui.window.newEditor";

    /**
     * The tag Eclipse gives its editor stacks ({@code PartStackUtil.EDITOR_STACK_TAG}, internal):
     * a stack made here gets it too, so Eclipse treats it as one of its own (tab style, the rule
     * that keeps the last editor stack).
     */
    static final String EDITOR_STACK_TAG = "EditorStack";

    /** The editor active before the current one, for {@code Ctrl-W p}; weak, so a closed one goes. */
    private static WeakReference<IEditorPart> previous = new WeakReference<>(null);
    private static WeakReference<IEditorPart> last = new WeakReference<>(null);

    private EditorSplits() {
    }

    /**
     * An editor became the active one ({@code EditorAttachManager}'s part listener), so the one
     * before it is {@code Ctrl-W p}'s.
     */
    static void activated(IEditorPart part) {
        if (part != null && part != last.get()) {
            previous = last;
            last = new WeakReference<>(part);
        }
    }

    /**
     * Carries out {@code command}, typed in {@code editor}. A command that cannot be carried out
     * says why in the editor's message line, as Vim shows an error.
     */
    static void run(ITextEditor editor, WindowCommand command) {
        if (command == null) {
            HostCommands.showError(editor, "window: not a window command");
            return;
        }
        IEditorPart part = EditorAttachManager.getInstance().workbenchPartOf(editor);
        try {
            switch (command.action()) {
                case SPLIT -> split(editor, part, command);
                case ONLY -> only(part);
                case FOCUS -> focus(editor, part, command);
                case SIZE -> size(editor, part, command);
                case EXCHANGE -> exchange(editor, part, command.count());
                case ROTATE -> rotate(editor, part, command.key() == 'r', command.count());
            }
        } catch (IllegalStateException e) {
            HostCommands.showError(editor, e.getMessage()); // Vim's own error (E443)
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Window command " + command + " failed", e);
            HostCommands.showError(editor, "Window command failed: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    /** Whether the editor area shows more than one editor: a split, of either kind. */
    static boolean isSplit(IEditorPart part) {
        Picture picture = Picture.of(part);
        return picture != null && WindowLayout.windows(picture.root).size() > 1;
    }

    // ---- :split, :vsplit, :new, :sbuffer, Ctrl-W s, v, n, ^, f ----

    /**
     * Opens the split's editor (a clone of this one, another file, or an untitled one) and moves it
     * into a new stack on the given side of this editor's stack, as dragging its tab there would.
     * Vim's new window takes half of the current one: so does the new stack. The new editor is the
     * active one, and Neovim attaches to it, with a window of its own.
     */
    private static void split(ITextEditor editor, IEditorPart from, WindowCommand command) {
        IWorkbenchPage page = from.getSite().getPage();
        MPart fromPart = from.getSite().getService(MPart.class);
        MPartStack fromStack = stackOf(fromPart);
        EModelService models = from.getSite().getService(EModelService.class);
        if (fromStack == null || models == null) {
            HostCommands.showError(editor, "Cannot split: this editor is not in an editor stack");
            return;
        }
        MStackElement fromTab = fromStack.getSelectedElement(); // the editor, or Eclipse's halves around it
        IEditorPart opened = openFor(editor, from, command);
        if (opened == null || opened == from) {
            return; // not opened: the reason is shown already
        }
        MPart openedPart = opened.getSite().getService(MPart.class);
        Shell shell = from.getSite().getShell();
        shell.setRedraw(false); // one redraw for the new stack, not one per step
        try {
            MPartStack stack = models.createModelElement(MPartStack.class);
            stack.getTags().add(EDITOR_STACK_TAG);
            models.insert(stack, fromStack, side(command.where()), 0.5f);
            models.move(openedPart, stack);
            stack.setSelectedElement(openedPart);
            if (fromTab != null && fromStack.getChildren().contains(fromTab)) {
                fromStack.setSelectedElement(fromTab); // the editor split from stays in front in its stack
            }
        } finally {
            shell.setRedraw(true);
        }
        page.activate(opened);
        opened.setFocus();
    }

    /** {@link EModelService}'s side for a split's {@link WindowCommand.Where}. */
    private static int side(WindowCommand.Where where) {
        return switch (where) {
            case ABOVE -> EModelService.ABOVE;
            case BELOW -> EModelService.BELOW;
            case LEFT -> EModelService.LEFT_OF;
            case RIGHT -> EModelService.RIGHT_OF;
        };
    }

    /**
     * The new editor of a split, or {@code null} with the reason shown. It opens in this editor's
     * stack, and {@link #split} then moves it.
     *
     * <ul>
     *   <li>The same file: <i>Window &gt; Editor &gt; Clone</i>, which copies the caret and the
     *       scroll position, as Vim's new window starts where the old one is. Neovim then takes the
     *       caret as the cursor of the clone's own window (two windows on one buffer).</li>
     *   <li>Another file: a new editor even when one has it open already (Eclipse's
     *       {@code MATCH_NONE}), since a Vim window of its own is what was asked for. A file that
     *       does not exist opens as a new one, saved by the first {@code :w}, as for {@code :e}.</li>
     *   <li>{@code :new}: a new untitled editor, as {@code :enew} opens.</li>
     * </ul>
     */
    private static IEditorPart openFor(ITextEditor editor, IEditorPart from, WindowCommand command) {
        IWorkbenchPage page = from.getSite().getPage();
        switch (command.target()) {
            case SAME -> {
                try {
                    from.getSite().getService(IHandlerService.class).executeCommand(CLONE_COMMAND, null);
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Clone failed", e);
                    HostCommands.showError(editor, "Cannot split: Window > Editor > Clone failed");
                    return null;
                }
            }
            case NEW -> EclipseFileCommands.newUntitled(editor);
            case FILE -> {
                // Usually a full path already: Neovim expanded the name as its own :split would.
                Path home = Path.of(System.getProperty("user.home"));
                Path folder = EclipseFileCommands.windowFolder(editor);
                Path file = EditPath.resolve(command.file(), home, folder);
                if (file == null) {
                    HostCommands.showError(editor, "Can't find file \"" + command.file() + "\"");
                    return null;
                }
                String shown = EditPath.shown(file, home, folder);
                if (Files.isDirectory(file)) {
                    HostCommands.showError(editor, "\"" + shown + "\" is a directory");
                    return null;
                }
                if (!Files.exists(file)) {
                    NewFiles.open(page, file, shown); // a dangling link too, as for :e (Files.exists follows links)
                } else {
                    return EclipseFileCommands.open(page, file, IWorkbenchPage.MATCH_NONE);
                }
            }
        }
        return page.getActiveEditor();
    }

    // ---- :only, Ctrl-W o ----

    /**
     * Vim's {@code :only}: this editor alone on screen. Nothing is closed, since an Eclipse editor
     * may hold changes no Vim window would lose: the other stacks' editors become tabs of this
     * editor's stack (their emptied stacks go away), and Eclipse's split halves are joined again.
     */
    static void only(IEditorPart part) {
        MPart current = part.getSite().getService(MPart.class);
        MPartStack keep = stackOf(current);
        EModelService models = part.getSite().getService(EModelService.class);
        MArea area = areaOf(current);
        if (keep == null || models == null || area == null) {
            return;
        }
        Shell shell = part.getSite().getShell();
        shell.setRedraw(false);
        try {
            MStackElement tab = keep.getSelectedElement();
            for (MPartStack stack : models.findElements(area, null, MPartStack.class, null)) {
                if (stack != keep) {
                    for (MStackElement element : List.copyOf(stack.getChildren())) {
                        models.move(element, keep);
                    }
                }
            }
            for (MPart split : models.findElements(area, null, MPart.class, null)) {
                // Eclipse's Toggle Split Editor turned off, as its own handler does it: by the tags.
                split.getTags().remove(IPresentationEngine.SPLIT_HORIZONTAL);
                split.getTags().remove(IPresentationEngine.SPLIT_VERTICAL);
            }
            if (tab != null && keep.getChildren().contains(tab)) {
                keep.setSelectedElement(tab);
            }
        } finally {
            shell.setRedraw(true);
        }
        IWorkbenchPage page = part.getSite().getPage();
        if (page.getActiveEditor() != null) {
            page.activate(page.getActiveEditor()); // joining halves may have closed the active one
            page.getActiveEditor().setFocus();
        }
    }

    // ---- Ctrl-W h j k l w W t b p ----

    private static void focus(ITextEditor editor, IEditorPart part, WindowCommand command) {
        Picture picture = Picture.of(part);
        if (picture == null || picture.current < 0) {
            return;
        }
        Point caret = caretOnScreen(editor);
        IEditorPart before = previous.get();
        int previousId = before == null ? -1 : picture.idOf(before);
        int target = WindowLayout.focus(picture.root, picture.current, previousId, command.key(), command.count(),
                caret.x, caret.y);
        if (target != picture.current) {
            IEditorPart next = picture.editors.get(target);
            part.getSite().getPage().activate(next);
            next.setFocus();
        }
    }

    /** The caret's place in display pixels, which {@code Ctrl-W l} keeps the row of. */
    private static Point caretOnScreen(ITextEditor editor) {
        StyledText text = (StyledText) editor.getAdapter(Control.class);
        if (text == null || text.isDisposed()) {
            return new Point(0, 0);
        }
        Point at = text.getLocationAtOffset(text.getCaretOffset());
        return text.toDisplay(at.x, at.y + text.getLineHeight() / 2);
    }

    // ---- Ctrl-W = + - < > _ |, :resize ----

    private static void size(ITextEditor editor, IEditorPart part, WindowCommand command) {
        Picture picture = Picture.of(part);
        StyledText text = (StyledText) editor.getAdapter(Control.class);
        if (picture == null || picture.current < 0 || text == null || text.isDisposed()) {
            return;
        }
        Rectangle area = text.getClientArea();
        int charWidth;
        GC gc = new GC(text);
        try {
            charWidth = Math.max(1, (int) Math.round(gc.getFontMetrics().getAverageCharacterWidth()));
        } finally {
            gc.dispose();
        }
        WindowLayout.Metrics metrics = new WindowLayout.Metrics(text.getLineHeight(), charWidth, area.height, area.width);
        boolean heights = "+-_".indexOf(command.key()) >= 0;
        // A window keeps one line (or character) of text, with its tab row and rulers around it.
        WindowLayout.Rect window = picture.boundsOf(picture.current);
        int minimum = heights ? metrics.lineHeight() + Math.max(0, window.height() - area.height)
                : metrics.charWidth() + Math.max(0, window.width() - area.width);
        for (WindowLayout.Sizes sizes : WindowLayout.resize(picture.root, picture.current, command.key(),
                command.count(), metrics, minimum)) {
            List<MUIElement> children = picture.groupChildren.get(sizes.group());
            WindowLayout.Group group = (WindowLayout.Group) WindowLayout.find(picture.root, sizes.group());
            int[] before = group.children().stream()
                    .mapToInt(n -> group.sideBySide() ? n.bounds().width() : n.bounds().height()).toArray();
            int[] weights = WindowLayout.weights(sizes.sizes(), before,
                    children.stream().mapToInt(EditorSplits::weightOf).toArray());
            for (int i = 0; i < children.size(); i++) {
                children.get(i).setContainerData(String.valueOf(weights[i]));
            }
        }
    }

    /** An element's weight in its sash container ({@code containerData}), or -1 when it has none. */
    private static int weightOf(MUIElement element) {
        try {
            return element.getContainerData() == null ? -1 : Integer.parseInt(element.getContainerData().trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ---- Ctrl-W x, r, R ----

    /**
     * Vim's {@code Ctrl-W x}: this editor and the next one in its row or column change places; the
     * cursor stays where it was on screen, so the other editor is then the active one, as in Vim.
     */
    private static void exchange(ITextEditor editor, IEditorPart part, int count) {
        Picture picture = Picture.of(part);
        if (picture == null || picture.current < 0) {
            return;
        }
        int other = WindowLayout.exchangeWith(picture.root, picture.current, count);
        if (other < 0) {
            return; // nothing to swap with, as in Vim
        }
        MPartStack here = picture.stackOf(picture.current);
        MPartStack there = picture.stackOf(other);
        if (here == null || there == null) {
            HostCommands.showError(editor, "Eclipse's split halves cannot change places");
            return;
        }
        EModelService models = part.getSite().getService(EModelService.class);
        moveTabs(part, models, List.of(here, there), List.of(there, here));
        activate(part.getSite().getPage(), picture.editors.get(other));
    }

    /** Vim's {@code Ctrl-W r} and {@code R}: the editors of this row or column move one place on. */
    private static void rotate(ITextEditor editor, IEditorPart part, boolean down, int count) {
        Picture picture = Picture.of(part);
        if (picture == null || picture.current < 0) {
            return;
        }
        List<Integer> order = WindowLayout.rotated(picture.root, picture.current, down, count);
        if (order.isEmpty()) {
            return;
        }
        // Position i of the row gets the editor of window order[i].
        List<MPartStack> places = new ArrayList<>();
        List<MPartStack> from = new ArrayList<>();
        WindowLayout.Group parent = WindowLayout.parentOf(picture.root, picture.current);
        for (int i = 0; i < order.size(); i++) {
            MPartStack place = picture.stackOf(((WindowLayout.Window) parent.children().get(i)).id());
            MPartStack source = picture.stackOf(order.get(i));
            if (place == null || source == null) {
                HostCommands.showError(editor, "Eclipse's split halves cannot change places");
                return;
            }
            places.add(place);
            from.add(source);
        }
        EModelService models = part.getSite().getService(EModelService.class);
        moveTabs(part, models, from, places);
        activate(part.getSite().getPage(), part); // the cursor stays with its window, as in Vim
    }

    /**
     * Moves the front tab of each stack in {@code from} to the stack at the same place in
     * {@code to}, where it becomes the front tab. All tabs are taken first, so a stack can give
     * its tab and get another in the same step.
     */
    private static void moveTabs(IEditorPart part, EModelService models, List<MPartStack> from, List<MPartStack> to) {
        List<MStackElement> tabs = from.stream().map(MPartStack::getSelectedElement).toList();
        Shell shell = part.getSite().getShell();
        shell.setRedraw(false);
        try {
            for (int i = 0; i < tabs.size(); i++) {
                if (tabs.get(i) != null && from.get(i) != to.get(i)) {
                    models.move(tabs.get(i), to.get(i));
                }
            }
            for (int i = 0; i < tabs.size(); i++) {
                if (tabs.get(i) != null) {
                    to.get(i).setSelectedElement(tabs.get(i));
                }
            }
        } finally {
            shell.setRedraw(true);
        }
    }

    private static void activate(IWorkbenchPage page, IEditorPart editor) {
        page.activate(editor);
        editor.setFocus();
    }

    // ---- The model ----

    /** The editor stack an editor is in: its parent, or the stack around Eclipse's split halves. */
    static MPartStack stackOf(MUIElement element) {
        for (MUIElement at = element; at != null; at = parentOf(at)) {
            if (at instanceof MPartStack stack) {
                return stack;
            }
        }
        return null;
    }

    /** The editor area an editor is in, or {@code null} (an editor dragged out of it). */
    static MArea areaOf(MUIElement element) {
        for (MUIElement at = element; at != null; at = parentOf(at)) {
            if (at instanceof MArea area) {
                return area;
            }
        }
        return null;
    }

    /** The element's container; for a shared element shown through a placeholder, the placeholder's. */
    private static MUIElement parentOf(MUIElement element) {
        if (element.getParent() != null) {
            return element.getParent();
        }
        return element.getCurSharedRef() == null ? null : element.getCurSharedRef().getParent();
    }

    /**
     * The editor area drawn as a {@link WindowLayout} tree, with each node's model element: what
     * the area shows now (each stack's front editor, the visible children of each sash container).
     */
    private static final class Picture {
        WindowLayout.Node root;
        /** The node id of the current editor, -1 if it is not on screen. */
        int current = -1;
        /** The editor of each window node. */
        final Map<Integer, IEditorPart> editors = new HashMap<>();
        /** The element whose {@code containerData} is each node's weight: a stack, a half, a sash container. */
        final Map<Integer, MUIElement> weighted = new HashMap<>();
        /** For each group node, its children's weighted elements, in order. */
        final Map<Integer, List<MUIElement>> groupChildren = new HashMap<>();
        private final Map<MPart, IEditorPart> partsToEditors = new IdentityHashMap<>();
        private int nextId;

        /** The picture of the editor area {@code part} is in, or {@code null} when it is in none. */
        static Picture of(IEditorPart part) {
            MPart current = part.getSite().getService(MPart.class);
            MArea area = current == null ? null : areaOf(current);
            if (area == null) {
                return null;
            }
            Picture picture = new Picture();
            for (IEditorReference reference : part.getSite().getPage().getEditorReferences()) {
                IEditorPart editor = reference.getEditor(false); // an editor never shown has no widgets
                MPart model = editor == null ? null : editor.getSite().getService(MPart.class);
                if (model != null) {
                    picture.partsToEditors.put(model, editor);
                }
            }
            picture.root = picture.node(area, area);
            if (picture.root == null) {
                return null;
            }
            picture.current = picture.idOf(part);
            return picture;
        }

        /** The node of {@code element}, whose weight is {@code weightedAs}'s; {@code null} if nothing shows. */
        private WindowLayout.Node node(MUIElement element, MUIElement weightedAs) {
            if (!element.isToBeRendered() || !element.isVisible()) {
                return null;
            }
            if (element instanceof MPartStack stack) {
                MStackElement front = stack.getSelectedElement();
                return front == null ? null : node(front, stack);
            }
            if (element instanceof MPart part && !(element instanceof MCompositePart)) {
                IEditorPart editor = partsToEditors.get(part);
                WindowLayout.Rect bounds = boundsOf(part);
                if (editor == null || bounds == null) {
                    return null; // a view in the editor area, or an editor not drawn
                }
                int id = nextId++;
                editors.put(id, editor);
                weighted.put(id, weightedAs);
                return new WindowLayout.Window(id, bounds);
            }
            if (element instanceof MElementContainer<?> container) {
                int id = nextId++;
                List<WindowLayout.Node> children = new ArrayList<>();
                List<MUIElement> childElements = new ArrayList<>();
                for (MUIElement child : container.getChildren()) {
                    WindowLayout.Node node = node(child, child);
                    if (node != null) {
                        children.add(node);
                        childElements.add(child);
                    }
                }
                if (children.isEmpty()) {
                    return null;
                }
                boolean sideBySide = container instanceof MGenericTile<?> tile && tile.isHorizontal();
                weighted.put(id, weightedAs);
                groupChildren.put(id, childElements);
                return new WindowLayout.Group(id, sideBySide, children);
            }
            return null;
        }

        /** An editor's bounds in display pixels, or {@code null} when it is not drawn. */
        private static WindowLayout.Rect boundsOf(MPart part) {
            if (!(part.getWidget() instanceof Control control) || control.isDisposed() || !control.isVisible()) {
                return null;
            }
            Rectangle r = control.getParent() == null ? control.getBounds()
                    : control.getDisplay().map(control.getParent(), null, control.getBounds());
            return r.width <= 0 || r.height <= 0 ? null : new WindowLayout.Rect(r.x, r.y, r.width, r.height);
        }

        int idOf(IEditorPart editor) {
            for (Map.Entry<Integer, IEditorPart> entry : editors.entrySet()) {
                if (entry.getValue() == editor) {
                    return entry.getKey();
                }
            }
            return -1;
        }

        WindowLayout.Rect boundsOf(int window) {
            return WindowLayout.find(root, window).bounds();
        }

        /** The stack of a window that is a stack's front editor; {@code null} for one of Eclipse's halves. */
        MPartStack stackOf(int window) {
            return weighted.get(window) instanceof MPartStack stack ? stack : null;
        }
    }
}
