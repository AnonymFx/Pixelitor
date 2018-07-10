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
import pixelitor.tools.Tool;
import pixelitor.tools.Tools;
import pixelitor.tools.util.PPoint;
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
import java.awt.geom.Point2D;
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

    private ImageWindow imageWindow = null;

    private static final CheckerboardPainter checkerBoardPainter = ImageUtils.createCheckerboardPainter();

    private LayersPanel layersPanel;

    private Composition comp;

    private MaskViewMode maskViewMode;

    // the start coordinates of the canvas if the ImageComponent is bigger
    // than the canvas, and the canvas needs to be centralized
    private double canvasStartX;
    private double canvasStartY;

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
        comp.addAllLayersToGUI();
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
                // TODO this is now also done in setSize
                updateCanvasLocation();

                // one can zoom an inactive image with the mouse wheel,
                // but the tools expect an active image
                if (ImageComponents.isActive(ImageComponent.this)) {
                    Tools.icSizeChanged(ImageComponent.this);
                }
                
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
    public Dimension getMinimumSize() {
        return getPreferredSize();
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

    public void setImageWindow(ImageWindow window) {
        this.imageWindow = window;
        setImageWindowSize();
    }

    public ImageWindow getImageWindow() {
        return imageWindow;
    }

    public void close() {
        if (imageWindow != null) {
            // this will also cause the calling of AppLogic.imageClosed via
            // InternalImageFrame.internalFrameClosed
            imageWindow.dispose();
        }
        comp.dispose();
    }

    public void onActivation() {
        try {
            getImageWindow().setSelected(true);
        } catch (PropertyVetoException e) {
            Messages.showException(e);
        }
        LayersContainer.showLayersPanel(layersPanel);
    }

    public double getViewScale() {
        return viewScale;
    }

    public void updateTitle() {
        if (imageWindow != null) {
            String frameTitle = createFrameTitle();
            imageWindow.setTitle(frameTitle);
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

    public void changeLayerButtonOrder(int oldIndex, int newIndex) {
        layersPanel.changeLayerButtonOrder(oldIndex, newIndex);
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

        int canvasCoWidth = canvas.getCoWidth();
        int canvasCoHeight = canvas.getCoHeight();

        Rectangle canvasClip = setVisibleCanvasClip(g,
                canvasStartX, canvasStartY,
                canvasCoWidth, canvasCoHeight);

        // make a copy of the transform object
        AffineTransform componentTransform = g2.getTransform();

        g2.translate(canvasStartX, canvasStartY);

        boolean showMask = maskViewMode.showMask();
        if (!showMask) {
            checkerBoardPainter.paint(g2, this, canvasCoWidth, canvasCoHeight);
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

        if (ImageComponents.isActive(this)) {
            currentTool.paintOverImage(g2, canvas, this, componentTransform, imageTransform);
        }
        
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
            int x = (int) (canvasStartX + i);
            g2.drawLine(x, startY, x, endY);
        }

        // horizontal lines
        double skipHor = Math.ceil(startY / pixelSize);
        for (double i = skipHor * pixelSize; i < endY; i += pixelSize) {
            int y = (int) (canvasStartY + i);
            g2.drawLine(startX, y, endX, y);
        }
    }

    /**
     * Makes sure that not the whole area is repainted, only the canvas and that only
     * inside the visible area of scrollbars
     */
    private static Rectangle setVisibleCanvasClip(Graphics g, double canvasStartX, double canvasStartY, int maxWidth, int maxHeight) {
        // if there are scollbars, this is the visible area
        Rectangle clipBounds = g.getClipBounds();

        Rectangle imageRect = new Rectangle((int) canvasStartX, (int) canvasStartY, maxWidth, maxHeight);

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
        if (imageWindow != null) {
            imageWindow.makeSureItIsVisible();
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

    public void canvasCoSizeChanged() {
        assert ConsistencyChecks.imageCoversCanvas(comp);

        setImageWindowSize();
        updateCanvasLocation();
        revalidate(); // TODO also necessary with tabs?
    }

    public void setImageWindowSize() {
        if (imageWindow != null && imageWindow instanceof ImageFrame) {
            imageWindow.setSize(canvas.getCoWidth(), canvas.getCoHeight());
        }
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
        if (imageWindow != null) {
            updateTitle();
            int newFrameWidth = canvas.getCoWidth();
            int newFrameHeight = canvas.getCoHeight();
            imageWindow.setSize(newFrameWidth, newFrameHeight);

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

        // TODO is this necessary? - could call validate instead of revalidate
        // some flickering is present either way
        revalidate();

        Rectangle finalRect = areaThatShouldBeVisible;


        // we are already on the EDT, but we want to call this code
        // only after all pending AWT events have been processed
        // because then this component will have the final size
        // and updateCanvasLocation can calculate correct results

        // TODO updateCanvasLocation moved from here - scrollRectToVisible and
        // repaint still needs to run later?
        SwingUtilities.invokeLater(() -> {
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

    @Override
    public void setSize(int width, int height) {
        super.setSize(width, height);
        updateCanvasLocation();
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

    public void updateCanvasLocation() {
        canvasStartX = (getWidth() - canvas.getCoWidth()) / 2.0;
        canvasStartY = (getHeight() - canvas.getCoHeight()) / 2.0;

        // make the transforms invalid
        imToCo = null;
        coToIm = null;
    }

    @Override
    public double componentXToImageSpace(double coX) {
        return ((coX - canvasStartX) / viewScale);
    }

    @Override
    public double componentYToImageSpace(double coY) {
        return ((coY - canvasStartY) / viewScale);
    }

    @Override
    public double imageXToComponentSpace(double imX) {
        return canvasStartX + imX * viewScale;
    }

    @Override
    public double imageYToComponentSpace(double imY) {
        return canvasStartY + imY * viewScale;
    }

    @Override
    public Point2D componentToImageSpace(Point2D co) {
        return new Point2D.Double(
                componentXToImageSpace(co.getX()),
                componentYToImageSpace(co.getY()));
    }

    @Override
    public Point2D imageToComponentSpace(Point2D im) {
        return new Point2D.Double(
                imageXToComponentSpace(im.getX()),
                imageYToComponentSpace(im.getY()));
    }

    public Point fromComponentToImageSpace(Point co, ZoomLevel zoom) {
        double zoomViewScale = zoom.getViewScale();
        return new Point(
                (int) ((co.x - canvasStartX) / zoomViewScale),
                (int) ((co.y - canvasStartY) / zoomViewScale)
        );
    }

    public Point fromImageToComponentSpace(Point im, ZoomLevel zoom) {
        double zoomViewScale = zoom.getViewScale();
        return new Point(
                (int) (canvasStartX + im.x * zoomViewScale),
                (int) (canvasStartY + im.y * zoomViewScale)
        );
    }

    @Override
    public Rectangle2D componentToImageSpace(Rectangle co) {
        return new Rectangle.Double(
                componentXToImageSpace(co.x),
                componentYToImageSpace(co.y),
                (co.getWidth() / viewScale),
                (co.getHeight() / viewScale)
        );
    }

    @Override
    public Rectangle imageToComponentSpace(Rectangle2D im) {
        return new Rectangle(
                (int) imageXToComponentSpace(im.getX()),
                (int) imageYToComponentSpace(im.getY()),
                (int) (im.getWidth() * viewScale),
                (int) (im.getHeight() * viewScale)
        );
    }

    @Override
    public AffineTransform getImageToComponentTransform() {
        if (imToCo == null) {
            imToCo = new AffineTransform();
            imToCo.translate(canvasStartX, canvasStartY);
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
            coToIm.translate(-canvasStartX, -canvasStartY);
        }
        return coToIm;
    }

    /**
     * Returns how much of this ImageComponent is currently
     * visible considering that the JScrollPane might show
     * only a part of it
     */
    public Rectangle getVisiblePart() {
        return imageWindow.getScrollPane().getViewport().getViewRect();
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

    public double getCanvasStartX() {
        return canvasStartX;
    }

    public void setNavigator(Navigator navigator) {
        this.navigator = navigator;
    }

    public void updateNavigator(boolean icSizeChanged) {
        assert SwingUtilities.isEventDispatchThread();
        if (navigator != null) {
            if (icSizeChanged) {
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
