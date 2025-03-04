package org.plantuml.idea.toolwindow;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.LowMemoryWatcher;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.Alarm;
import org.jetbrains.annotations.NotNull;
import org.plantuml.idea.action.NextPageAction;
import org.plantuml.idea.action.SelectPageAction;
import org.plantuml.idea.action.ZoomAction;
import org.plantuml.idea.lang.settings.PlantUmlSettings;
import org.plantuml.idea.plantuml.ImageFormat;
import org.plantuml.idea.rendering.*;
import org.plantuml.idea.toolwindow.image.ImageContainer;
import org.plantuml.idea.toolwindow.image.ImageContainerPng;
import org.plantuml.idea.toolwindow.image.ImageContainerSvg;
import org.plantuml.idea.toolwindow.image.links.Highlighter;
import org.plantuml.idea.toolwindow.listener.PlantUmlAncestorListener;
import org.plantuml.idea.util.UIUtils;
import org.plantuml.idea.util.Utils;

import javax.swing.*;
import javax.swing.event.AncestorListener;
import java.awt.*;
import java.awt.event.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.plantuml.idea.rendering.LazyApplicationPoolExecutor.Delay.NOW;
import static org.plantuml.idea.rendering.LazyApplicationPoolExecutor.Delay.RESET_DELAY;

/**
 * @author Eugene Steinberg
 */
public class PlantUmlToolWindow extends JPanel implements Disposable {
    private static Logger logger = Logger.getInstance(PlantUmlToolWindow.class);

    private ToolWindow toolWindow;
    private JPanel imagesPanel;
    private JScrollPane scrollPane;

    private Zoom zoom;
    private int selectedPage = -1;

    private RenderCache renderCache;

    private AncestorListener plantUmlAncestorListener;

    private final LazyApplicationPoolExecutor lazyExecutor;

    private Project project;
    private AtomicInteger sequence = new AtomicInteger();
    public ExecutionStatusPanel executionStatusPanel;
    private SelectedPagePersistentStateComponent selectedPagePersistentStateComponent;
    private FileEditorManager fileEditorManager;
    private FileDocumentManager fileDocumentManager;
    private VirtualFileManager fileManager;

    private int lastValidVerticalScrollValue;
    private int lastValidHorizontalScrollValue;
    private LocalFileSystem localFileSystem;
    private Highlighter highlighter;
    private Alarm zoomAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD);
    public Alarm backgroundZoomAlarm;
    private final PlantUmlSettings settings;

    public PlantUmlToolWindow(Project project, final ToolWindow toolWindow) {
        super(new BorderLayout());
        this.project = project;
        this.toolWindow = toolWindow;
        settings = PlantUmlSettings.getInstance();
        zoom = new Zoom(toolWindow.getComponent(), 100, settings);

        // Make sure settings are loaded and applied before we start rendering.
        renderCache = new RenderCache(settings.getCacheSizeAsInt());
        selectedPagePersistentStateComponent = ServiceManager.getService(SelectedPagePersistentStateComponent.class);
        plantUmlAncestorListener = new PlantUmlAncestorListener(this, project);
        fileEditorManager = FileEditorManager.getInstance(project);
        fileDocumentManager = FileDocumentManager.getInstance();
        fileManager = VirtualFileManager.getInstance();
        localFileSystem = LocalFileSystem.getInstance();

        setupUI();
        lazyExecutor = new LazyApplicationPoolExecutor(settings.getRenderDelayAsInt(), executionStatusPanel);
        LowMemoryWatcher.register(new Runnable() {
            @Override
            public void run() {
                renderCache.clear();
                if (renderCache.getDisplayedItem() != null && !toolWindow.isVisible()) {
                    renderCache.setDisplayedItem(null);
                    imagesPanel.removeAll();
                    imagesPanel.add(new JLabel("Low memory detected, cache and images cleared. Go to PlantUML plugin settings and set lower cache size, or increase IDE heap size (-Xmx)."));
                    imagesPanel.revalidate();
                    imagesPanel.repaint();
                }
            }
        }, this);

        //must be last
        this.toolWindow.getComponent().addAncestorListener(plantUmlAncestorListener);

        applyNewSettings(settings);
        highlighter = new Highlighter();
        backgroundZoomAlarm = new Alarm(Alarm.ThreadToUse.POOLED_THREAD, this);
    }

    private void setupUI() {
        DefaultActionGroup newGroup = getActionGroup();
        final ActionToolbar actionToolbar = ActionManager.getInstance().createActionToolbar(ActionPlaces.UNKNOWN, newGroup, true);
        actionToolbar.setTargetComponent(this);
        add(actionToolbar.getComponent(), BorderLayout.PAGE_START);

        imagesPanel = new JPanel();
        imagesPanel.setLayout(new BoxLayout(imagesPanel, BoxLayout.Y_AXIS));

        scrollPane = new JBScrollPane(imagesPanel);
        scrollPane.getVerticalScrollBar().setUnitIncrement(20);
        scrollPane.getVerticalScrollBar().addAdjustmentListener(new AdjustmentListener() {
            @Override
            public void adjustmentValueChanged(AdjustmentEvent adjustmentEvent) {
                if (!adjustmentEvent.getValueIsAdjusting()) {
                    RenderCacheItem displayedItem = getDisplayedItem();
                    if (displayedItem != null && !displayedItem.getRenderResult().hasError()) {
                        lastValidVerticalScrollValue = adjustmentEvent.getValue();
                    }
                }
            }
        });
        scrollPane.getHorizontalScrollBar().addAdjustmentListener(new AdjustmentListener() {
            @Override
            public void adjustmentValueChanged(AdjustmentEvent adjustmentEvent) {
                if (!adjustmentEvent.getValueIsAdjusting()) {
                    RenderCacheItem displayedItem = getDisplayedItem();
                    if (displayedItem != null && !displayedItem.getRenderResult().hasError()) {
                        lastValidHorizontalScrollValue = adjustmentEvent.getValue();
                    }
                }

            }
        });
        imagesPanel.add(new Usage("Usage:\n"));

        add(scrollPane, BorderLayout.CENTER);

        addScrollBarListeners(imagesPanel);
    }

    @NotNull
    private DefaultActionGroup getActionGroup() {
        DefaultActionGroup group = (DefaultActionGroup) ActionManager.getInstance().getAction("PlantUML.Toolbar");
        DefaultActionGroup newGroup = new DefaultActionGroup();
        AnAction[] childActionsOrStubs = group.getChildActionsOrStubs();
        for (int i = 0; i < childActionsOrStubs.length; i++) {
            AnAction stub = childActionsOrStubs[i];
            newGroup.add(stub);
            if (stub instanceof ActionStub) {
                if (((ActionStub) stub).getClassName().equals(NextPageAction.class.getName())) {
                    newGroup.add(new SelectPageAction(this));
                }
            }
        }
        executionStatusPanel = new ExecutionStatusPanel();
        newGroup.add(executionStatusPanel);
        return newGroup;
    }

    private void addScrollBarListeners(JComponent panel) {
        MouseWheelListener l = new MouseWheelListener() {
            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                if (e.isControlDown()) {
                    changeZoom(Math.max(zoom.getUnscaledZoom() - e.getWheelRotation() * 10, 1), e.getPoint());
                } else {
                    e.setSource(scrollPane);
                    scrollPane.dispatchEvent(e);
                }
                e.consume();
            }
        };
        MouseMotionListener l1 = new MouseMotionListener() {
            private int x, y;

            @Override
            public void mouseDragged(MouseEvent e) {
                JScrollBar h = scrollPane.getHorizontalScrollBar();
                JScrollBar v = scrollPane.getVerticalScrollBar();

                int dx = x - e.getXOnScreen();
                int dy = y - e.getYOnScreen();

                h.setValue(h.getValue() + dx);
                v.setValue(v.getValue() + dy);

                x = e.getXOnScreen();
                y = e.getYOnScreen();
                e.consume();
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                x = e.getXOnScreen();
                y = e.getYOnScreen();
            }
        };
        panel.addMouseWheelListener(l);
        panel.addMouseMotionListener(l1);
        for (Component component : Utils.getAllComponents(panel)) {
            component.addMouseWheelListener(l);
            component.addMouseMotionListener(l1);
        }
    }


    @Override
    public void dispose() {
        logger.debug("dispose");
        removeAllImages();
        toolWindow.getComponent().removeAncestorListener(plantUmlAncestorListener);
    }

    private Alarm myAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD);

    public void processRequest(final LazyApplicationPoolExecutor.Delay delay, final RenderCommand.Reason reason) {
        if (!toolWindow.isVisible()) {
            logger.debug("tool window not visible, aborting");
            return;
        }
        Runnable renderRunnable = () -> {
            logger.debug("processRequest ", project.getName(), " ", delay, " ", reason);
            if (isProjectValid(project)) {

                String source = UIUtils.getSelectedSourceWithCaret(fileEditorManager);
                String sourceFilePath = null;
                RenderCacheItem cachedItem = null;

                if ("".equals(source)) { //is included file or some crap?
                    logger.debug("empty source");
                    cachedItem = renderCache.getDisplayedItem();
                    if (cachedItem == null) {
                        logger.debug("no DisplayedItem, empty source, reason=", reason);
                        return;
                    }

                    source = cachedItem.getSource();
                    sourceFilePath = cachedItem.getSourceFilePath();
                } else {
                    VirtualFile selectedFile = UIUtils.getSelectedFile(fileEditorManager, fileDocumentManager);
                    if (selectedFile != null) {
                        sourceFilePath = selectedFile.getPath();
                    } else {
                        sourceFilePath = "DUMMY_NO_PATH";
                    }
                }

                selectedPage = selectedPagePersistentStateComponent.getPage(sourceFilePath);
                zoom = zoom.refresh(this, this.settings);

                logger.debug("setting selected page from storage ", selectedPage);

                if (reason == RenderCommand.Reason.REFRESH) {
                    logger.debug("executing command, reason=", reason);
                    lazyExecutor.execute(getCommand(RenderCommand.Reason.REFRESH, sourceFilePath, source, selectedPage, zoom, null, delay));
                    return;
                }

                RenderCacheItem betterItem = renderCache.getCachedItem(sourceFilePath, source, selectedPage, zoom, fileDocumentManager, fileManager);
                logger.debug("cacheItem ", betterItem);
                if (betterItem != null) {
                    cachedItem = betterItem;
                }

                if (cachedItem == null) {
                    logger.debug("no cached item");
                    lazyExecutor.execute(getCommand(reason, sourceFilePath, source, selectedPage, zoom, null, delay));
                } else if (cachedItem.includedFilesChanged(fileDocumentManager, fileManager)) {
                    logger.debug("includedFilesChanged");
                    lazyExecutor.execute(getCommand(RenderCommand.Reason.INCLUDES, sourceFilePath, source, selectedPage, zoom, cachedItem, delay));
                } else if (cachedItem.imageMissingOrZoomChanged(selectedPage, zoom)) {
                    logger.debug("render required imageMissingOrZoomChanged");
                    lazyExecutor.execute(getCommand(RenderCommand.Reason.SOURCE_PAGE_ZOOM, sourceFilePath, source, selectedPage, zoom, cachedItem, delay));
                } else if (cachedItem.sourceChanged(source)) {
                    logger.debug("render required sourceChanged");
                    lazyExecutor.execute(getCommand(RenderCommand.Reason.SOURCE_PAGE_ZOOM, sourceFilePath, source, selectedPage, zoom, cachedItem, RESET_DELAY));
                } else if (!renderCache.isDisplayed(cachedItem, selectedPage)) {
                    logger.debug("render not required, displaying cached item ", cachedItem);
                    displayExistingDiagram(cachedItem);
                } else {
                    logger.debug("render not required, item already displayed ", cachedItem);
                    if (reason != RenderCommand.Reason.CARET) {
                        cachedItem.setVersion(sequence.incrementAndGet());
                        lazyExecutor.cancel();
                        executionStatusPanel.updateNow(cachedItem.getVersion(), ExecutionStatusPanel.State.DONE, "cached");
                    }
                }
            }
        };

        int i = myAlarm.cancelAllRequests();
        myAlarm.addRequest(Utils.logDuration("EDT processRequest", renderRunnable), delay == NOW ? 0 : 10);
    }

    public void displayExistingDiagram(RenderCacheItem last) {
        last.setVersion(sequence.incrementAndGet());
        last.setRequestedPage(selectedPage);
        executionStatusPanel.updateNow(last.getVersion(), ExecutionStatusPanel.State.DONE, "cached");
        displayDiagram(last, false);
    }


    @NotNull
    protected RenderCommand getCommand(RenderCommand.Reason reason, String selectedFile, final String source, final int page, final Zoom zoom, RenderCacheItem cachedItem, LazyApplicationPoolExecutor.Delay delay) {
        logger.debug("#getCommand selectedFile='", selectedFile, "', page=", page, ", scaledZoom=", zoom);
        int version = sequence.incrementAndGet();

        return new MyRenderCommand(reason, selectedFile, source, page, zoom, cachedItem, version, delay, executionStatusPanel);
    }

    public boolean isToolWindowVisible() {
        return toolWindow.isVisible();
    }

    public void highlightImages(Editor editor) {
        if (editor == null) {
            return;
        }
        highlighter.highlightImages(this, editor);
    }


    private class MyRenderCommand extends RenderCommand {

        public MyRenderCommand(Reason reason, String selectedFile, String source, int page, Zoom zoom, RenderCacheItem cachedItem, int version, LazyApplicationPoolExecutor.Delay delay, ExecutionStatusPanel label) {
            super(project, reason, selectedFile, source, page, zoom, cachedItem, version, delay, label);
        }

        @Override
        public void displayResultOnEDT(RenderCacheItem newItem, long total, RenderResult result) {
            try {
                if (reason == Reason.REFRESH) {
                    if (cachedItem != null) {
                        renderCache.removeFromCache(cachedItem);
                    }
                }
                if (!newItem.getRenderResult().hasError()) {
                    renderCache.addToCache(newItem);
                }
                logger.debug("displaying item ", newItem);

                if (silentError(newItem)) {
                    executionStatusPanel.updateNow(newItem.getVersion(), ExecutionStatusPanel.State.ERROR, total, result, new SwitchBetweenOldImageAndSilentError(newItem));
                } else {
                    boolean updateStatus = displayDiagram(newItem, false);
                    if (updateStatus) {
                        executionStatusPanel.updateNow(newItem.getVersion(), ExecutionStatusPanel.State.DONE, total, result, new SwitchBetweenCurrentErrorAndOldImage(newItem));
                    }
                }
            } catch (Throwable e) {
                executionStatusPanel.updateNow(newItem.getVersion(), ExecutionStatusPanel.State.ERROR, total, result, null);
                logger.error(e);
            }
        }

        private boolean silentError(RenderCacheItem newItem) {
            return renderCache.getDisplayedItem() != null
                    && !renderCache.getDisplayedItem().getRenderResult().hasError()
                    && newItem.getRenderResult().hasError()
                    && settings.isDoNotDisplayErrors();
        }

        private class SwitchBetweenCurrentErrorAndOldImage implements Runnable {

            private RenderCacheItem oldDiagram;
            private boolean hasError;

            public SwitchBetweenCurrentErrorAndOldImage(RenderCacheItem newItem) {
                hasError = newItem.getRenderResult().hasError();
            }

            @Override
            public void run() {
                if (hasError) {
                    final RenderCacheItem displayedItem = renderCache.getDisplayedItem();
                    if (oldDiagram != null && displayedItem != oldDiagram) {
                        displayDiagram(oldDiagram, true);
                        SwingUtilities.invokeLater(() -> oldDiagram = null);
                    } else {
                        RenderCacheItem renderCacheItem = renderCache.getLast();
                        if (renderCacheItem != null && displayedItem != renderCacheItem) {
                            displayDiagram(renderCacheItem, true);
                            SwingUtilities.invokeLater(() -> oldDiagram = displayedItem);
                        }
                    }
                }
            }
        }

        private class SwitchBetweenOldImageAndSilentError implements Runnable {

            private final RenderCacheItem newItem;
            private RenderCacheItem oldDiagram;

            public SwitchBetweenOldImageAndSilentError(RenderCacheItem newItem) {
                this.newItem = newItem;
            }

            @Override
            public void run() {
                final RenderCacheItem displayedItem = renderCache.getDisplayedItem();

                if (oldDiagram != null && displayedItem != oldDiagram) {
                    displayDiagram(oldDiagram, true);
                    SwingUtilities.invokeLater(() -> oldDiagram = null);
                } else if (displayedItem != newItem) {
                    displayDiagram(newItem, true);
                    SwingUtilities.invokeLater(() -> oldDiagram = displayedItem);

                }
            }
        }
    }

    public boolean displayDiagram(RenderCacheItem cacheItem, boolean force) {
        if (!force && renderCache.isOlderRequest(cacheItem)) { //ctrl+z with cached image vs older request in progress
            logger.debug("skipping displaying older result", cacheItem);
            return false;
        }
        long start = System.currentTimeMillis();

        //maybe track position per file?
        RenderCacheItem displayedItem = renderCache.getDisplayedItem();
        boolean restoreScrollPosition = displayedItem != null && displayedItem.getRenderResult().hasError() && renderCache.isSameFile(cacheItem);
        //must be before revalidate
        int lastValidVerticalScrollValue = this.lastValidVerticalScrollValue;
        int lastValidHorizontalScrollValue = this.lastValidHorizontalScrollValue;


        renderCache.setDisplayedItem(cacheItem);

        ImageItem[] imageItems = cacheItem.getImageItems();
        RenderResult renderResult = cacheItem.getRenderResult();
        int requestedPage = cacheItem.getRequestedPage();

        if (requestedPage >= renderResult.getPages()) {
            logger.debug("requestedPage >= renderResult.getPages()", requestedPage, ">=", renderResult.getPages());
            requestedPage = -1;
            if (!renderResult.hasError()) {
                logger.debug("toolWindow.page=", requestedPage, " (previously page=", selectedPage, ")");
                selectedPage = requestedPage;
            }
        }

        if (requestedPage == -1) {
            boolean incrementalDisplay = cacheItem.getRenderRequest().getReason() != RenderCommand.Reason.REFRESH && renderCache.isSameFile(cacheItem);
            logger.debug("displaying images ", requestedPage, ", incrementalDisplay=", incrementalDisplay);

            Component[] children = imagesPanel.getComponents();
            if (incrementalDisplay && children.length == renderResult.getPages() * 2) {
                for (int i = 0; i < imageItems.length; i++) {
                    Component child = children[i * 2];
                    ImageItem imageItem = imageItems[i];
                    if (child instanceof ImageContainer) {
                        ImageContainer container = (ImageContainer) child;
                        if (container.getImageItem() == imageItem) {
                            continue;
                        } else {
                            Disposer.dispose(container);
                            imagesPanel.remove(i * 2);
                        }
                    } else {
                        imagesPanel.remove(i * 2);
                    }
                    JComponent component = createImageContainer(cacheItem, i, imageItem);
                    imagesPanel.add(component, i * 2);
                }
            } else {
                removeAllImages();
                for (int i = 0; i < imageItems.length; i++) {
                    displayImage(cacheItem, i, imageItems[i]);
                }
            }
        } else {
            logger.debug("displaying image ", requestedPage);
            removeAllImages();
            displayImage(cacheItem, requestedPage, imageItems[requestedPage]);
        }

        if (settings.isHighlightInImages()) {
            highlighter.highlightImages(this, UIUtils.getSelectedTextEditor(fileEditorManager));
        }


        imagesPanel.revalidate();
        imagesPanel.repaint();

        //would be nice without a new event :(
        if (restoreScrollPosition) {
            //hope concurrency wont be an issue
            SwingUtilities.invokeLater(new Runnable() {
                public void run() {
                    scrollPane.getVerticalScrollBar().setValue(lastValidVerticalScrollValue);
                    scrollPane.getHorizontalScrollBar().setValue(lastValidHorizontalScrollValue);
                }
            });
        }

        logger.debug("EDT displayImages done in ", System.currentTimeMillis() - start, "ms");

        return true;
    }

    private void removeAllImages() {
        long start = System.currentTimeMillis();
        Component[] children = imagesPanel.getComponents();
        imagesPanel.removeAll();
        for (Component component : children) {
            if (component instanceof Disposable) {
                Disposer.dispose((Disposable) component);
            }
        }
        logger.debug("removeAllImages done in ", System.currentTimeMillis() - start, "ms");
    }

    public JScrollPane getScrollPane() {
        return scrollPane;
    }

    public void displayImage(RenderCacheItem cacheItem, int pageNumber, ImageItem imageWithData) {
        JComponent component = createImageContainer(cacheItem, pageNumber, imageWithData);

        imagesPanel.add(component);
        imagesPanel.add(separator());
    }

    @NotNull
    private JComponent createImageContainer(RenderCacheItem cacheItem, int pageNumber, ImageItem imageWithData) {
        if (imageWithData == null) {
            throw new RuntimeException("trying to display null image. selectedPage=" + selectedPage + ", nullPage=" + pageNumber + ", cacheItem=" + cacheItem);
        }
        JComponent component = null;
        if (imageWithData.getException() != null) {
            component = new JTextArea(Utils.stacktraceToString(imageWithData.getException()));
        } else if (imageWithData.getFormat() == ImageFormat.SVG) {
            component = new ImageContainerSvg(project, imageWithData, pageNumber, cacheItem.getRenderRequest(), cacheItem.getRenderResult());
        } else {
            component = new ImageContainerPng(project, imagesPanel, imageWithData, pageNumber, cacheItem.getRenderRequest(), cacheItem.getRenderResult());
        }
        addScrollBarListeners(component);
        return component;
    }


    public void applyNewSettings(PlantUmlSettings plantUmlSettings) {
        lazyExecutor.setDelay(plantUmlSettings.getRenderDelayAsInt());
        renderCache.setMaxCacheSize(plantUmlSettings.getCacheSizeAsInt());
    }

    private JSeparator separator() {
        JSeparator separator = new JSeparator(SwingConstants.HORIZONTAL);
        Dimension size = new Dimension(separator.getPreferredSize().width, 10);
        separator.setVisible(true);
        separator.setMaximumSize(size);
        separator.setPreferredSize(size);
        return separator;
    }


    @NotNull
    public Zoom getZoom() {
        return zoom;
    }

    public void changeZoom(int unscaledZoom, Point point) {
        unscaledZoom = Math.min(ZoomAction.MAX_ZOOM, unscaledZoom);
        int oldUnscaled = zoom.getUnscaledZoom();
        //do always, so that changed OS scaling takes effect
        zoom = new Zoom(this, unscaledZoom, settings);

        if (oldUnscaled == unscaledZoom) {
            return;
        }

        logger.debug("changing zoom to unscaledZoom=", unscaledZoom);

        if (settings.isDisplaySvg()) {
            int i = zoomAlarm.cancelAllRequests();
            int finalUnscaledZoom = unscaledZoom;
            zoomAlarm.addRequest(() -> {
                for (Component component : imagesPanel.getComponents()) {
                    if (component instanceof ImageContainerSvg) {
                        ((ImageContainerSvg) component).setZoomOptimized(finalUnscaledZoom, point);
                    }
                }

            }, 10);
        } else {
            processRequest(LazyApplicationPoolExecutor.Delay.NOW, RenderCommand.Reason.SOURCE_PAGE_ZOOM);
        }

        WindowManager.getInstance().getStatusBar(project).setInfo("Zoomed changed to " + unscaledZoom + "%");
    }

    public void setSelectedPage(int selectedPage) {
        if (selectedPage >= -1 && selectedPage < getNumPages()) {
            logger.debug("page ", selectedPage, " selected");
            this.selectedPage = selectedPage;
            selectedPagePersistentStateComponent.setPage(selectedPage, renderCache.getDisplayedItem());
            processRequest(LazyApplicationPoolExecutor.Delay.NOW, RenderCommand.Reason.SOURCE_PAGE_ZOOM);
        }
    }

    public void nextPage() {
        setSelectedPage(this.selectedPage + 1);
    }

    public void prevPage() {
        setSelectedPage(this.selectedPage - 1);
    }

    public int getNumPages() {
        int pages = -1;
        RenderCacheItem last = renderCache.getDisplayedItem();
        if (last != null) {
            RenderResult imageResult = last.getRenderResult();
            if (imageResult != null) {
                pages = imageResult.getPages();
            }
        }
        return pages;
    }

    public int getSelectedPage() {
        return selectedPage;
    }

    public RenderCacheItem getDisplayedItem() {
        return renderCache.getDisplayedItem();
    }

    private boolean isProjectValid(Project project) {
        return project != null && !project.isDisposed();
    }


    public JPanel getImagesPanel() {
        return imagesPanel;
    }


}

