package lodgebilling.view;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.scene.Node;
import javafx.util.Duration;

/** The one animation the screens share: a form settling into place when a screen opens. */
public final class Motion {

    private static final Duration ENTER_TIME = Duration.millis(220);
    private static final double RISE = 8;

    private Motion() { }

    /** Fades the parts in while they rise a few pixels into place. */
    public static void enter(Node... parts) {
        for (Node part : parts) {
            part.setOpacity(0);
            part.setTranslateY(RISE);

            FadeTransition fade = new FadeTransition(ENTER_TIME, part);
            fade.setToValue(1);
            TranslateTransition rise = new TranslateTransition(ENTER_TIME, part);
            rise.setToY(0);

            ParallelTransition entrance = new ParallelTransition(fade, rise);
            entrance.setInterpolator(Interpolator.EASE_OUT);
            entrance.play();
        }
    }
}
