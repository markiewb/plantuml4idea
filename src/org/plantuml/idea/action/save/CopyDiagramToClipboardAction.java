package org.plantuml.idea.action.save;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.plantuml.idea.toolwindow.PlantUmlToolWindow;
import org.plantuml.idea.toolwindow.image.ImageContainer;
import org.plantuml.idea.util.UIUtils;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.IOException;

/**
 * @author Eugene Steinberg
 */
public class CopyDiagramToClipboardAction extends DumbAwareAction {


    @Override
    public void actionPerformed(AnActionEvent e) {
        final Project project = e.getProject();

        PlantUmlToolWindow umlToolWindow = UIUtils.getPlantUmlToolWindow(project);
        JPanel imagesPanel = umlToolWindow.getImagesPanel();
        ImageContainer component = (ImageContainer) imagesPanel.getComponent(0);
        final Image image = component.getPngImage();

        CopyPasteManager.getInstance().setContents(new Transferable() {
            @Override
            public DataFlavor[] getTransferDataFlavors() {
                return new DataFlavor[]{
                        DataFlavor.imageFlavor
                };
            }

            @Override
            public boolean isDataFlavorSupported(DataFlavor flavor) {
                return flavor.equals(DataFlavor.imageFlavor);
            }

            @Override
            public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException, IOException {
                if (!flavor.equals(DataFlavor.imageFlavor)) {
                    throw new UnsupportedFlavorException(flavor);
                }
                return image;
            }
        });
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        final Project project = e.getProject();
        if (project != null) {
            PlantUmlToolWindow umlToolWindow = UIUtils.getPlantUmlToolWindow(e.getProject());
            if (umlToolWindow != null) {
                int selectedPage = umlToolWindow.getSelectedPage();
                JPanel imagesPanel = umlToolWindow.getImagesPanel();
                Component component1 = imagesPanel.getComponent(0);
                if (component1 instanceof ImageContainer) {
                    ImageContainer component = (ImageContainer) component1;
                    boolean pngAvailable = component.isPngAvailable();
                    boolean singlePage = umlToolWindow.getNumPages() == 1 || (umlToolWindow.getNumPages() > 1 && selectedPage != -1);
                    e.getPresentation().setEnabled(pngAvailable && singlePage);
                }
            } else {
                e.getPresentation().setEnabled(false);

            }
        }
    }
}
