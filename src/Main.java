import java.awt.GraphicsDevice;
import java.awt.KeyboardFocusManager;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

public final class Main {
    static JFrame frame;
    static Game game;
    static boolean full;
    static Rectangle windowed;

    public static void main(String[] args) {
        System.setProperty("sun.java2d.uiScale.enabled", "true");
        Audio.start();
        Audio.music(Audio.MUSIC_DUEL);
        SwingUtilities.invokeLater(() -> {
            // fit inside the work area (screen minus taskbar) with room to spare: a window that nearly fills it
            // gets maximized by Windows, which stretches the view and letterboxes it
            Rectangle area = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
            double scale = Math.min(1.0, Math.min((area.width - 60) / 1280.0, (area.height - 70) / 720.0));
            JFrame f = new JFrame(Lang.windowTitle());
            frame = f;
            game = new Game(scale);
            f.setIconImages(java.util.Arrays.asList(Sprites.icon(16), Sprites.icon(32), Sprites.icon(64), Sprites.icon(128)));
            f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            f.add(game);
            f.pack();
            f.setLocationRelativeTo(null);
            f.setVisible(true);
            f.setExtendedState(JFrame.NORMAL);
            windowed = f.getBounds();
            // remember the plain window's place and size, to come back to it from full screen
            f.addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override public void componentResized(java.awt.event.ComponentEvent e) { keep(); }
                @Override public void componentMoved(java.awt.event.ComponentEvent e) { keep(); }
                void keep() { if (!full && f.getExtendedState() == JFrame.NORMAL && f.isDisplayable()) windowed = f.getBounds(); }
            });
            // maximizing leaves a work area that is not 16:9 (title bar, taskbar), so it would letterbox:
            // go borderless full screen instead, which fills a 16:9 monitor exactly
            f.addWindowStateListener(e -> {
                if (!full && (e.getNewState() & JFrame.MAXIMIZED_BOTH) == JFrame.MAXIMIZED_BOTH) SwingUtilities.invokeLater(() -> setFull(true));
            });
            KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
                if (e.getID() != KeyEvent.KEY_PRESSED || e.getKeyCode() != KeyEvent.VK_F11) return false;
                setFull(!full);
                return true;
            });
            game.requestFocusInWindow();
        });
    }

    /** F11 (or the maximize button): borderless full screen; F11 again goes back to the window. */
    static void setFull(boolean on) {
        if (on == full) return;
        JFrame f = frame;
        GraphicsDevice dev = f.getGraphicsConfiguration().getDevice();
        f.dispose();
        if (on) {
            f.setExtendedState(JFrame.NORMAL);
            f.setUndecorated(true);
            f.setBounds(f.getGraphicsConfiguration().getBounds());
            f.setVisible(true);
            dev.setFullScreenWindow(f);
        } else {
            dev.setFullScreenWindow(null);
            f.setUndecorated(false);
            f.setExtendedState(JFrame.NORMAL);
            if (windowed != null) f.setBounds(windowed); else { f.pack(); f.setLocationRelativeTo(null); }
            f.setVisible(true);
        }
        full = on;
        game.requestFocusInWindow();
    }
}
