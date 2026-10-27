// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.IAnnotationAccessExtension;
import org.eclipse.ui.texteditor.DefaultMarkerAnnotationAccess;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.Diagnostic.Severity;

/**
 * Which annotations in an editor are problems, and how bad: the {@code severity} function of
 * {@code sync/DiagnosticsSync}.
 *
 * <h2>Annotation types</h2>
 * Every annotation has a type, a string, and types form a hierarchy declared in {@code plugin.xml}
 * files ({@code org.eclipse.ui.editors.annotationTypes}). The platform declares the three problem
 * types, {@code org.eclipse.ui.workbench.texteditor.error}, {@code .warning} and {@code .info}; other
 * plug-ins declare subtypes. Example: JDT's {@code org.eclipse.jdt.ui.error}, given to the problems it
 * finds while typing, has {@code ...texteditor.error} as its super type, so it is an error here too.
 * A problem marker of any builder (Checkstyle, a language server's) shows as an annotation of the
 * platform's type for its severity. Asking "is it a subtype of error?" rather than comparing strings
 * covers them all without knowing them.
 *
 * <p>Not problems: tasks ({@code ...texteditor.task}, the Tasks view's business), bookmarks, search
 * results, occurrences, spelling, quick diff; none of them is a subtype of the three.
 *
 * <p>{@link DefaultMarkerAnnotationAccess} answers the subtype question from the workbench's registry
 * ({@link IAnnotationAccessExtension#isSubtype}). It needs the workbench, so this class stays in
 * {@code editor/}; the tests of the sync use plain type names instead.
 */
final class ProblemAnnotations {

    static final String ERROR = "org.eclipse.ui.workbench.texteditor.error";
    static final String WARNING = "org.eclipse.ui.workbench.texteditor.warning";
    static final String INFO = "org.eclipse.ui.workbench.texteditor.info";

    private static final IAnnotationAccessExtension ACCESS = new DefaultMarkerAnnotationAccess();

    private ProblemAnnotations() {
    }

    /** The severity of a problem annotation, or {@code null} for any other annotation. */
    static Severity severityOf(Annotation annotation) {
        Object type = annotation.getType();
        if (type == null) {
            return null;
        }
        // Most severe first: a type is a subtype of one of the three at most, but be safe.
        if (ACCESS.isSubtype(type, ERROR)) {
            return Severity.ERROR;
        }
        if (ACCESS.isSubtype(type, WARNING)) {
            return Severity.WARN;
        }
        if (ACCESS.isSubtype(type, INFO)) {
            return Severity.INFO;
        }
        return null;
    }
}
