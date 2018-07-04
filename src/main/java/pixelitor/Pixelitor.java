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

package pixelitor;

import com.bric.util.JVM;
import net.jafama.FastMath;
import pixelitor.colors.FgBgColors;
import pixelitor.colors.FillType;
import pixelitor.filters.Filter;
import pixelitor.gui.GUIMessageHandler;
import pixelitor.gui.ImageComponent;
import pixelitor.gui.ImageComponents;
import pixelitor.gui.PixelitorWindow;
import pixelitor.gui.utils.Dialogs;
import pixelitor.io.OpenSaveManager;
import pixelitor.layers.AddLayerMaskAction;
import pixelitor.layers.AddTextLayerAction;
import pixelitor.layers.Layer;
import pixelitor.layers.LayerMaskAddType;
import pixelitor.layers.MaskViewMode;
import pixelitor.tools.Tool;
import pixelitor.tools.Tools;
import pixelitor.tools.pen.Path;
import pixelitor.utils.Messages;
import pixelitor.utils.Shapes;
import pixelitor.utils.Utils;

import javax.swing.*;
import javax.swing.plaf.MenuBarUI;
import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.TimeUnit;

/**
 * The main class
 */
public class Pixelitor {
    /**
     * Should not be instantiated
     */
    private Pixelitor() {
    }

    public static void main(String[] args) {
        // the app can be put into development mode by
        // adding -Dpixelitor.development=true to the command line
        if ("true".equals(System.getProperty("pixelitor.development"))) {
            Utils.checkThatAssertionsAreEnabled();
            Build.CURRENT = Build.DEVELOPMENT;
        }

        System.setProperty("com.apple.mrj.application.apple.menu.about.name", "Pixelitor");

        if (JVM.isLinux) {
            // doesn't seem to pick up good defaults
            System.setProperty("awt.useSystemAAFontSettings", "lcd");
            System.setProperty("swing.aatext", "true");
        }

        ExceptionHandler.INSTANCE.initialize();
        EventQueue.invokeLater(() -> {
            try {
                createAndShowGUI(args);

                // Start this thread here because it is IO-intensive and
                // it should not slow down the loading of the GUI
                new Thread(Pixelitor::preloadFontNames)
                        .start();
            } catch (Exception e) {
                Dialogs.showExceptionDialog(e);
            }
        });

        // Force the initialization of FastMath look-up tables now
        // on the main thread, so that later no unexpected delays happen.
        // This is OK because static initializers are thread safe.
        FastMath.cos(0.1);
    }

    private static void preloadFontNames() {
        GraphicsEnvironment localGE = GraphicsEnvironment.getLocalGraphicsEnvironment();
        // the results are cached, no need to cache them here
        localGE.getAvailableFontFamilyNames();
    }

    private static void createAndShowGUI(String[] args) {
        assert SwingUtilities.isEventDispatchThread() : "not EDT thread";

        setLookAndFeel();
        Messages.setMessageHandler(new GUIMessageHandler());

        PixelitorWindow pw = PixelitorWindow.getInstance();
        Dialogs.setMainWindowInitialized(true);

//        if (JVM.isMac) {
//            setupMacMenuBar(pw);
//        }

        if (args.length > 0) {
            openFilesGivenAsCLArguments(args);
        }

        // Just to make 100% sure that at the end of GUI
        // initialization the focus is not grabbed by
        // a textfield and the keyboard shortcuts work properly
        FgBgColors.getGUI()
                .requestFocus();

        TipsOfTheDay.showTips(pw, false);

        afterStartTestActions(pw);
    }

    // used to work but in newer Macs it doesn't
    private static void setupMacMenuBar(PixelitorWindow pw) {
        JMenuBar menuBar = pw.getJMenuBar();
        try {
            // this property is respected only by the Aqua look-and-feel...
            System.setProperty("apple.laf.useScreenMenuBar", "true");
            // ...so set the look-and-feel for the menu only to Aqua

            //noinspection ClassNewInstance
            menuBar.setUI((MenuBarUI) Class.forName("com.apple.laf.AquaMenuBarUI")
                    .newInstance());
        } catch (Exception e) {
            // ignore
        }
    }

    private static void setLookAndFeel() {
        try {
            String lfClass = getLFClassName();
            UIManager.setLookAndFeel(lfClass);
        } catch (Exception e) {
            Dialogs.showExceptionDialog(e);
        }
    }

    private static void openFilesGivenAsCLArguments(String[] args) {
        for (String fileName : args) {
            File f = new File(fileName);
            if (f.exists()) {
                OpenSaveManager.openFile(f);
            } else {
                Messages.showError("File not found", "The file \"" + f.getAbsolutePath() + "\" does not exist");
            }
        }
    }

    /**
     * A possibility for automatic debugging or testing
     */
    @SuppressWarnings("UnusedParameters")
    private static void afterStartTestActions(PixelitorWindow pw) {
        if (Build.CURRENT == Build.FINAL) {
            // in the final builds nothing should run
            return;
        }

//        addTestPath();

//        keepSwitchingToolsRandomly();
//        startFilter(new Marble());

//        Navigator.showInDialog(pw);

//        clickTool(Tools.PEN);
//        addMaskAndShowIt();

//        showAddTextLayerDialog();

//        AutoPaint.showDialog();

//        Tests3x3.addStandardImage(false);

//        ImageComponents.getActiveIC().setZoom(ZoomLevel.Z6400, true);

//        GlobalKeyboardWatch.registerDebugMouseWatching();

//        new TweenWizard().start(pw);

//        pw.dispatchEvent(new KeyEvent(pw, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), KeyEvent.CTRL_MASK, KeyEvent.VK_T, 'T'));
    }

    private static void addTestPath() {
        Rectangle2D.Double shape = new Rectangle2D.Double(100, 100, 300, 100);

        Path path = Shapes.shapeToPath(shape, ImageComponents.getActiveIC());
        Tools.PEN.setPath(path);
        Tools.PEN.startEditing(false);
        Tools.PEN.getButton().doClick();
    }

    private static void showAddTextLayerDialog() {
        AddTextLayerAction.INSTANCE.actionPerformed(null);
    }

    private static void addMaskAndShowIt() {
        AddLayerMaskAction.INSTANCE.actionPerformed(null);
        ImageComponent ic = ImageComponents.getActiveIC();
        Layer layer = ic.getComp()
                .getActiveLayer();
        MaskViewMode.SHOW_MASK.activate(ic, layer);
    }

    private static void clickTool(Tool tool) {
        tool.getButton().doClick();
    }

    private static void startFilter(Filter filter) {
        filter.startOn(ImageComponents.getActiveDrawableOrNull());
    }

    private static void addNewImage() {
        NewImage.addNewImage(FillType.WHITE, 600, 400, "Test");
        ImageComponents.getActiveLayerOrNull()
                .addMask(LayerMaskAddType.PATTERN);
    }

    private static void keepSwitchingToolsRandomly() {
        Runnable backgroundTask = () -> {
            //noinspection InfiniteLoopStatement
            while (true) {
                Utils.sleep(1, TimeUnit.SECONDS);
                // this will run on a background thread
                // and keeps putting this EDT task on the EDT
                Runnable switchTask = () -> {
                    Tool newTool = Tools.getRandomTool();
                    clickTool(newTool);
                };
                try {
                    SwingUtilities.invokeAndWait(switchTask);
                } catch (InterruptedException | InvocationTargetException e) {
                    e.printStackTrace();
                }
            }
        };
        new Thread(backgroundTask).start();
    }

    public static String getLFClassName() {
        return "javax.swing.plaf.nimbus.NimbusLookAndFeel";
    }
}
