/*
 * Copyright 2018 Laszlo Balazs-Csiki and Contributors
 *
 * This file is part of Pixelitor. Pixelitor is free software: you
 * can redistribute it and/or modify it under the terms of the GNU
 * General Public License, version 3 as published by the Free
 * Software Foundation.
 *
 * Pixelitor is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Pixelitor. If not, see <http://www.gnu.org/licenses/>.
 */

package pixelitor.tools.gradient.history;

import pixelitor.Composition;
import pixelitor.history.History;
import pixelitor.history.ImageEdit;
import pixelitor.history.PixelitorEdit;
import pixelitor.tools.Tools;
import pixelitor.tools.gradient.Gradient;

import javax.swing.undo.CannotRedoException;
import javax.swing.undo.CannotUndoException;

public class NewGradientEdit extends PixelitorEdit {
    private final Gradient gradient;
    private final ImageEdit imageEdit;

    public NewGradientEdit(Composition comp, Gradient gradient) {
        super("Create Gradient", comp);
        this.gradient = gradient;

        imageEdit = ImageEdit.create(comp, "", true);
    }

    @Override
    public void undo() throws CannotUndoException {
        super.undo();

        Tools.GRADIENT.setGradient(null, false, comp.getIC());
        imageEdit.undo();

        History.notifyMenus(this);
    }

    @Override
    public void redo() throws CannotRedoException {
        super.redo();

        // this needs to be called even if the gradient is regenerated,
        // in order to maintain the image edit's internal state
        imageEdit.redo();

        // set the handles
        Tools.GRADIENT.setGradient(gradient, false, comp.getIC());

        History.notifyMenus(this);
    }
}
