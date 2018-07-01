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

package pixelitor.gui;

import org.jdesktop.swingx.painter.CheckerboardPainter;
import pixelitor.AppLogic;
import pixelitor.Canvas;
import pixelitor.Composition;
import pixelitor.ConsistencyChecks;
import pixelitor.gui.utils.Dialogs;
import pixelitor.history.CompositionReplacedEdit;
import pixelitor.history.DeselectEdit;
import pixelitor.history.LinkedEdit;
import pixelitor.history.PixelitorEdit;
import pixelitor.layers.Layer;
import pixelitor.layers.LayerButton;
import pixelitor.layers.LayerMask;
import pixelitor.layers.LayersContainer;
import pixelitor.layers.LayersPanel;
import pixelitor.layers.MaskViewMode;
import pixelitor.menus.view.ZoomControl;
import pixelitor.menus.view.ZoomLevel;
import pixelitor.tools.PPoint;
import pixelitor.tools.Tool;
import pixelitor.tools.Tools;
import pixelitor.utils.ImageUtils;
import pixelitor.utils.Messages;
import pixelitor.utils.debug.ImageComponentNode;
import pixelitor.utils.test.Assertions;

import javax.swing.*;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.beans.PropertyVetoException;

import static java.awt.Color.BLACK;

/**
 * The GUI component that shows a composition
 */
public class ImageComponent extends JComponent implements MouseListener, MouseMotionListener, View {
    private double viewScale = 1.0f;
    private Canvas canvas;
    private ZoomLevel zoomLevel = ZoomLevel.Z100;

    private ImageFrame frame = null;

    private static final CheckerboardPainter checkerBoardPainter = ImageUtils.createCheckerboardPainter();

    private LayersPanel layersPanel;

    private Composition comp;

    private MaskViewMode maskViewMode;

    // the start of the image if the ImageComponent is resized to bigger
    // than the canvas, and the image needs to be centralized
    private double drawStartX;
    private double drawStartY;

    private AffineTransform coToIm;
    private AffineTransform imToCo;

    private Navigator navigator;

    public static boolean showPixelGrid = false;

    public ImageComponent(Composition comp) {
        assert comp != null;

        this.comp = comp;
        this.canvas = comp.getCanvas();
        comp.setIC(this);

        ZoomLevel fitZoom = AutoZoom.SPACE.calcZoom(canvas, false);
        setZoom(fitZoom, true, null);

        layersPanel = new LayersPanel();

        addListeners();
    }

    public PixelitorEdit replaceComp(Composition newComp, boolean addToHistory, MaskViewMode newMaskViewMode) {
        assert newComp != null;
        PixelitorEdit edit = null;

        MaskViewMode oldMode = maskViewMode;

        Composition oldComp = comp;
        comp = newComp;

        // do this here so that the old comp is deselected before
        // its ic is set to null
        if (addToHistory) {
            PixelitorEdit replaceEdit = new CompositionReplacedEdit(
                    "Reload", this, oldComp, newComp, oldMode);
            if (oldComp.hasSelection()) {
                DeselectEdit deselectEdit = oldComp.createDeselectEdit();
                edit = new LinkedEdit("Reload", oldComp, deselectEdit, replaceEdit);
                oldComp.deselect(false);
            } else {
                edit = replaceEdit;
            }
        }

        oldComp.setIC(null);
        comp.setIC(this);
        canvas = newComp.getCanvas();

        // keep the zoom level, but reinitialize the
        // internal frame size
        setZoom(zoomLevel, true, null);

        // refresh the layer buttons
        layersPanel = new LayersPanel();
        comp.addLayersToGUI();
        LayersContainer.showLayersPanel(layersPanel);

        newMaskViewMode.activate(this, comp.getActiveLayer());
        updateNavigator(true);

        return edit;
    }

    private void addListeners() {
        addMouseListener(this);
        addMouseMotionListener(this);

        addMouseWheelListener(e -> {
            if (e.isControlDown()) {
                if (e.getWheelRotation() < 0) { // up, away from the user
                    increaseZoom(e.getPoint());
                } else {  // down, towards the user
                    decreaseZoom(e.getPoint());
                }
            }
        });

        // make sure that the image is drawn at the middle
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                updateDrawStart();

                Tools.icSizeChanged(ImageComponent.this);
                repaint();
            }
        });
    }

    public boolean isDirty() {
        return comp.isDirty();
    }

    @Override
    public Dimension getPreferredSize() {
        if (comp.isEmpty()) {
            return super.getPreferredSize();
        } else {
            return canvas.getCoSize();
        }
    }

    @Override
    public void mouseClicked(MouseEvent e) {
        Tools.EventDispatcher.mouseClicked(e, this);
    }

    @Override
    public void mouseEntered(MouseEvent e) {
//        mouseEntered is never used in the tools
    }

    @Override
    public void mouseExited(MouseEvent e) {
//        mouseExited is never used in the tools
    }

    @Override
    public void mousePressed(MouseEvent e) {
        Tools.EventDispatcher.mousePressed(e, this);
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        Tools.EventDispatcher.mouseReleased(e, this);
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        Tools.EventDispatcher.mouseDragged(e, this);
    }

    @Override
    public void mouseMoved(MouseEvent e) {
        Tools.EventDispatcher.mouseMoved(e, this);
    }

    public void setFrame(ImageFrame frame) {
        this.frame = frame;
    }

    public ImageFrame getFrame() {
        return frame;
    }

    public void close() {
        if (frame != null) {
            // this will also cause the calling of AppLogic.imageClosed via
            // InternalImageFrame.internalFrameClosed
            frame.dispose();
        }
        comp.dispose();
    }

    public void onActivation() {
        try {
            getFrame().setSelected(true);
        } catch (PropertyVetoException e) {
            Messages.showException(e);
        }
        LayersContainer.showLayersPanel(layersPanel);
    }

    public double getViewScale() {
        return viewScale;
    }

    public void updateTitle() {
        if (frame != null) {
            String frameTitle = createFrameTitle();
            frame.setTitle(frameTitle);
        }
    }

    public String createFrameTitle() {
        return comp.getName() + " - " + zoomLevel.toString();
    }

    public ZoomLevel getZoomLevel() {
        return zoomLevel;
    }

    public void deleteLayerButton(LayerButton button) {
        layersPanel.deleteLayerButton(button);
    }

    public Composition getComp() {
        return comp;
    }

    @Override
    public String getName() {
        return comp.getName();
    }

    public void changeLayerOrderInTheGUI(int oldIndex, int newIndex) {
        layersPanel.changeLayerOrderInTheGUI(oldIndex, newIndex);
    }

    @Override
    public void paint(Graphics g) {
        try {
            // no borders, no children, double-buffering is happening
            // in the parent
            paintComponent(g);
        } catch (OutOfMemoryError e) {
            Dialogs.showOutOfMemoryDialog(e);
        }
    }

    @Override
    public void paintComponent(Graphics g) {
        Shape originalClip = g.getClip();

        Graphics2D g2 = (Graphics2D) g;

        int zoomedWidth = canvas.getCoWidth();
        int zoomedHeight = canvas.getCoHeight();

        Rectangle canvasClip = setVisibleCanvasClip(g, drawStartX, drawStartY, zoomedWidth, zoomedHeight);

        AffineTransform componentTransform = g2.getTransform(); // a copy of the transform object

        g2.translate(drawStartX, drawStartY);

        boolean showMask = maskViewMode.showMask();
        if (!showMask) {
            checkerBoardPainter.paint(g2, this, zoomedWidth, zoomedHeight);
        }

        g2.scale(viewScale, viewScale);
        // after the translation and scaling, we are in "image space"

        if (showMask) {
            LayerMask mask = comp.getActiveLayer().getMask();
            assert mask != null : "no mask in " + maskViewMode;
            mask.paintLayerOnGraphics(g2, true);
        } else {
            BufferedImage compositeImage = comp.getCompositeImage();
            ImageUtils.drawImageWithClipping(g2, compositeImage);

            if (maskViewMode.showRuby()) {
                LayerMask mask = comp.getActiveLayer().getMask();
                assert mask != null : "no mask in " + maskViewMode;
                mask.paintAsRubylith(g2);
            }
        }

        Tool currentTool = Tools.getCurrent();
        // possibly allow a larger clip for the selections and tools
        currentTool.setClipFor(g2, this);

        comp.paintSelection(g2);

        AffineTransform imageTransform = g2.getTransform();

        // restore the original transform
        g2.setTransform(componentTransform);

        currentTool.paintOverImage(g2, canvas, this, componentTransform, imageTransform);

        g2.setClip(canvasClip);

        if (showPixelGrid && zoomLevel.allowPixelGrid() && !comp.showsSelection()) {
//        if (showPixelGrid && zoomLevel.allowPixelGrid()) {
            // for some reason this very slow if there is a selection visible
            // and the pixel grid might not be shown anyway
            drawPixelGrid(g2);
        }

        g2.setClip(originalClip);
    }

    private void drawPixelGrid(Graphics2D g2) {
        g2.setXORMode(BLACK);
        double pixelSize = zoomLevel.getViewScale();

        Rectangle r = getVisiblePart();

        int startX = r.x;
        int endX = r.x + r.width;
        int startY = r.y;
        int endY = r.y + r.height;

        // vertical lines
        double skipVer = Math.ceil(startX / pixelSize);
        for (double i = pixelSize * skipVer; i < endX; i += pixelSize) {
            int x = (int) (drawStartX + i);
            g2.drawLine(x, startY, x, endY);
        }

        // horizontal lines
        double skipHor = Math.ceil(startY / pixelSize);
        for (double i = skipHor * pixelSize; i < endY; i += pixelSize) {
            int y = (int) (drawStartY + i);
            g2.drawLine(startX, y, endX, y);
        }
    }

    /**
     * Makes sure that not the whole area is repainted, only the canvas and that only
     * inside the visible area of scrollbars
     */
    private static Rectangle setVisibleCanvasClip(Graphics g, double drawStartX, double drawStartY, int maxWidth, int maxHeight) {
        // if there are scollbars, this is the visible area
        Rectangle clipBounds = g.getClipBounds();

        Rectangle imageRect = new Rectangle((int) drawStartX, (int) drawStartY, maxWidth, maxHeight);

        // now we are definitely not drawing neither outside
        // the canvas nor outside the scrollbars visible area
        clipBounds = clipBounds.intersection(imageRect);

        g.setClip(clipBounds);
        return clipBounds;
    }

    /**
     * Repaints only a region of the image, called from the brush tools
     */
    public void updateRegion(PPoint start, PPoint end, int thickness) {
        int startX = start.getCoX();
        int startY = start.getCoY();
        int endX = end.getCoX();
        int endY = end.getCoY();

        // make sure that the start coordinates are smaller
        if (endX < startX) {
            int tmp = startX;
            startX = endX;
            endX = tmp;
        }
        if (endY < startY) {
            int tmp = startY;
            startY = endY;
            endY = tmp;
        }

        // the thickness is derived from the brush radius, therefore
        // it still needs to be converted into component space
        thickness = (int) (viewScale * thickness);

        startX -= thickness;
        endX += thickness;
        startY -= thickness;
        endY += thickness;

        int repWidth = endX - startX;
        int repHeight = endY - startY;

        repaint(startX, startY, repWidth, repHeight);
    }

    public void makeSureItIsVisible() {
        if (frame != null) {
            frame.makeSureItIsVisible();
        }
    }

    public MaskViewMode getMaskViewMode() {
        return maskViewMode;
    }

    public boolean setMaskViewMode(MaskViewMode maskViewMode) {
        // it is important not to call this directly,
        // it should be a part of a mask activation
        assert Assertions.callingClassIs("MaskViewMode");
        assert maskViewMode.canBeAssignedTo(comp.getActiveLayer());

        MaskViewMode oldMode = this.maskViewMode;
        this.maskViewMode = maskViewMode;

        boolean change = oldMode != maskViewMode;
        if (change) {
            repaint();
        }
        return change;
    }

    public void canvasSizeChanged() {
        assert ConsistencyChecks.imageCoversCanvas(comp);

        if (frame != null) {
            frame.setSize(-1, -1, canvas.getCoWidth(), canvas.getCoHeight());
        }
        revalidate();
    }

    public Canvas getCanvas() {
        return canvas;
    }

    public void zoomToFit(AutoZoom autoZoom) {
        ZoomLevel bestZoom = autoZoom.calcZoom(canvas, true);
        setZoom(bestZoom, true, null);
    }

    /**
     * Sets the new zoom level
     */
    public void setZoom(ZoomLevel newZoom, boolean forceSettingSize, Point mousePos) {
        ZoomLevel oldZoom = zoomLevel;
        if (oldZoom == newZoom && !forceSettingSize) {
            // if forceSettingSize is true, we continue
            // in order to set the frame size
            return;
        }

        this.zoomLevel = newZoom;

        viewScale = newZoom.getViewScale();
        canvas.changeZooming(viewScale);
        imToCo = null;
        coToIm = null;

        Rectangle areaThatShouldBeVisible = null;
        if (frame != null) {
            updateTitle();
            int newFrameWidth = canvas.getCoWidth();
            int newFrameHeight = canvas.getCoHeight();
            frame.setSize(-1, -1, newFrameWidth, newFrameHeight);

            Rectangle visiblePart = getVisiblePart();

            // Update the scrollbars.
            Point origin;
            if (mousePos != null) { // we had a mouse click
                origin = mousePos;
            } else {
                int cx = visiblePart.x + visiblePart.width / 2;
                int cy = visiblePart.y + visiblePart.height / 2;

                origin = new Point(cx, cy);
            }
            // the x, y coordinates were generated BEFORE the zooming
            // so we need to find the corresponding coordinates after zooming
            // TODO maybe this would not be necessary if we did this earlier?
            Point imageSpaceOrigin = fromComponentToImageSpace(origin, oldZoom);
            origin = fromImageToComponentSpace(imageSpaceOrigin, newZoom);

            areaThatShouldBeVisible = new Rectangle(
                    origin.x - visiblePart.width / 2,
                    origin.y - visiblePart.height / 2,
                    visiblePart.width,
                    visiblePart.height
            );
        }

        revalidate();

        Rectangle finalRect = areaThatShouldBeVisible;

        // TODO is this necessary? - could call validate instead of revalidate
        // some flickering is present either way

        // we are already on the EDT, but we want to call this code
        // only after all pending AWT events have been processed
        // because then this component will have the final size
        // and updateDrawStart can calculate correct results
        SwingUtilities.invokeLater(() -> {
            updateDrawStart();
            if (finalRect != null) {
                scrollRectToVisible(finalRect);
            }
            repaint();
        });

        if (ImageComponents.getActiveIC() == this) {
            ZoomControl.INSTANCE.setToNewZoom(zoomLevel);
            zoomLevel.getMenuItem().setSelected(true);
        }
    }

    public void setZoomAtCenter(ZoomLevel newZoom) {
        setZoom(newZoom, false, null);
    }

    public void increaseZoom() {
        increaseZoom(null);
    }

    public void increaseZoom(Point mousePos) {
        ZoomLevel newZoom = zoomLevel.zoomIn();
        setZoom(newZoom, false, mousePos);
    }

    public void decreaseZoom() {
        decreaseZoom(null);
    }

    public void decreaseZoom(Point mousePos) {
        ZoomLevel newZoom = zoomLevel.zoomOut();
        setZoom(newZoom, false, mousePos);
    }

    public void updateDrawStart() {
        int width = getWidth();
        int canvasZoomedWidth = canvas.getCoWidth();
        int height = getHeight();
        int canvasZoomedHeight = canvas.getCoHeight();

        drawStartX = (width - canvasZoomedWidth) / 2.0;
        drawStartY = (height - canvasZoomedHeight) / 2.0;
        imToCo = null;
        coToIm = null;
    }

    @Override
    public double componentXToImageSpace(double mouseX) {
        return ((mouseX - drawStartX) / viewScale);
    }

    @Override
    public double componentYToImageSpace(double mouseY) {
        return ((mouseY - drawStartY) / viewScale);
    }

    @Override
    public double imageXToComponentSpace(double x) {
        return drawStartX + x * viewScale;
    }

    @Override
    public double imageYToComponentSpace(double y) {
        return drawStartY + y * viewScale;
    }

    public Point fromComponentToImageSpace(Point input, ZoomLevel zoom) {
        double zoomViewScale = zoom.getViewScale();
        return new Point(
                (int) ((input.x - drawStartX) / zoomViewScale),
                (int) ((input.y - drawStartY) / zoomViewScale)
        );
    }

    public Point fromImageToComponentSpace(Point input, ZoomLevel zoom) {
        double zoomViewScale = zoom.getViewScale();
        return new Point(
                (int) (drawStartX + input.x * zoomViewScale),
                (int) (drawStartY + input.y * zoomViewScale)
        );
    }

    @Override
    public Rectangle2D componentToImageSpace(Rectangle input) {
        return new Rectangle.Double(
                componentXToImageSpace(input.x),
                componentYToImageSpace(input.y),
                (input.getWidth() / viewScale),
                (input.getHeight() / viewScale)
        );
    }

    @Override
    public Rectangle imageToComponentSpace(Rectangle2D input) {
        return new Rectangle(
                (int) imageXToComponentSpace(input.getX()),
                (int) imageYToComponentSpace(input.getY()),
                (int) (input.getWidth() * viewScale),
                (int) (input.getHeight() * viewScale)
        );
    }

    @Override
    public AffineTransform getImageToComponentTransform() {
        if (imToCo == null) {
            imToCo = new AffineTransform();
            imToCo.translate(drawStartX, drawStartY);
            imToCo.scale(viewScale, viewScale);
        }
        return imToCo;
    }

    @Override
    public AffineTransform getComponentToImageTransform() {
        if (coToIm == null) {
            coToIm = new AffineTransform();
            double s = 1.0 / viewScale;
            coToIm.scale(s, s);
            coToIm.translate(-drawStartX, -drawStartY);
        }
        return coToIm;
    }

    /**
     * Returns how much of this ImageComponent is currently
     * visible considering that the JScrollPane might show
     * only a part of it
     */
    public Rectangle getVisiblePart() {
        return frame.getScrollPane().getViewport().getViewRect();
    }

    public void addLayerToGUI(Layer newLayer, int newLayerIndex) {
        LayerButton layerButton = newLayer.getUI();
        layersPanel.addLayerButton(layerButton, newLayerIndex);

        if (ImageComponents.isActive(this)) {
            AppLogic.numLayersChanged(comp, comp.getNumLayers());
        }
    }

    public boolean activeIsDrawable() {
        return comp.activeIsDrawable();
    }

    /**
     * The return value is changed only in unit tests
     */
    @SuppressWarnings({"MethodMayBeStatic", "SameReturnValue"})
    public boolean isMock() {
        return false;
    }

    public LayersPanel getLayersPanel() {
        return layersPanel;
    }

    public void setNavigator(Navigator navigator) {
        this.navigator = navigator;
    }

    public void updateNavigator(boolean newICSize) {
        assert SwingUtilities.isEventDispatchThread();
        if (navigator != null) {
            if (newICSize) {
                // defer until all
                // pending events have been processed
                SwingUtilities.invokeLater(() -> {
                    if (navigator != null) { // check again for safety
                        navigator.recalculateSize(this, false, true, false);
                    }
                });
            } else {
                // call here, painting calls will be coalesced anyway
                navigator.repaint();
            }
        }
    }

    @Override
    public String toString() {
        ImageComponentNode node = new ImageComponentNode("ImageComponent", this);
        return node.toDetailedString();
    }
}
