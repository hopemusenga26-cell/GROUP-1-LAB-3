package lodgebilling.view;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import javafx.animation.Animation;
import javafx.animation.AnimationTimer;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.scene.Group;
import javafx.scene.effect.BlendMode;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Paint;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

/**
 * The sign-in backdrop: an aurora night. A deep forest-green sky full of twinkling stars, curtains
 * of mint and green light made of thin vertical rays that ripple and flicker, the dark
 * outline of hills along the bottom and, now and then, a shooting star.
 * Everything is placed as a fraction of the window, so it fits any size.
 */
public class AuroraBackground extends Pane {

    /** One curtain of light: where it hangs, how tall it is and how it ripples. */
    private record Curtain(double baseY, double height, double wave, double waveLength,
                           double speed, Color top, Color body, Color hem) { }

    // The app's forest-to-mint greens, with one warm orange fringe like the app's main buttons
    private static final Curtain[] CURTAINS = {
        new Curtain(0.42, 0.30, 0.07, 0.9, 0.22,
                Color.web("#ec7a45"), Color.web("#4fc9a4"), Color.web("#daf1de")),
        new Curtain(0.55, 0.36, 0.09, 1.4, -0.16,
                Color.web("#235347"), Color.web("#8eb69b"), Color.web("#daf1de")),
        new Curtain(0.33, 0.22, 0.05, 0.7, 0.28,
                Color.web("#163832"), Color.web("#6fe3c6"), Color.web("#f1fbf3")),
    };
    private static final int RAYS_PER_CURTAIN = 110;
    private static final int STAR_COUNT = 170;
    // The same seed every time, so the stars and hills do not jump about between runs
    private static final long SEED = 261;

    private final Rectangle sky = new Rectangle();
    private final Rectangle horizonGlow = new Rectangle();
    private final List<Circle> stars = new ArrayList<>();
    private final double[][] starSpots;
    private final List<Rectangle[]> curtainRays = new ArrayList<>();
    private final Polygon hills = new Polygon();
    private final double[] hillHeights;
    private final Line shootingStar = new Line();
    private final List<Animation> motions = new ArrayList<>();
    private final AnimationTimer ripple;

    public AuroraBackground() {
        setMouseTransparent(true);
        Random random = new Random(SEED);

        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        setClip(clip);

        sky.widthProperty().bind(widthProperty());
        sky.heightProperty().bind(heightProperty());
        sky.setFill(new LinearGradient(0, 0, 0, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#010706")),
                new Stop(0.45, Color.web("#031312")),
                new Stop(0.80, Color.web("#051f20")),
                new Stop(1, Color.web("#0b2b26"))));

        // A faint green haze where the aurora meets the horizon
        horizonGlow.widthProperty().bind(widthProperty());
        horizonGlow.heightProperty().bind(heightProperty().multiply(0.45));
        horizonGlow.layoutYProperty().bind(heightProperty().multiply(0.55));
        horizonGlow.setFill(new LinearGradient(0, 0, 0, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.TRANSPARENT),
                new Stop(0.7, Color.web("#8eb69b", 0.12)),
                new Stop(1, Color.web("#8eb69b", 0.03))));

        Group starField = new Group();
        starSpots = new double[STAR_COUNT][2];
        for (int i = 0; i < STAR_COUNT; i++) {
            starSpots[i][0] = random.nextDouble();
            starSpots[i][1] = Math.pow(random.nextDouble(), 1.4) * 0.85;   // more stars high up
            Circle star = new Circle(0.4 + random.nextDouble() * 1.2, Color.web("#f4f8ff"));
            star.setOpacity(0.35 + random.nextDouble() * 0.65);
            stars.add(star);
            starField.getChildren().add(star);
            if (random.nextDouble() < 0.45) {
                FadeTransition twinkle = new FadeTransition(
                        Duration.seconds(1.2 + random.nextDouble() * 3), star);
                twinkle.setFromValue(star.getOpacity());
                twinkle.setToValue(0.08);
                twinkle.setDelay(Duration.seconds(random.nextDouble() * 3));
                twinkle.setAutoReverse(true);
                twinkle.setCycleCount(Animation.INDEFINITE);
                motions.add(twinkle);
            }
        }

        // Each curtain is a row of thin, softly blurred rays that add their light together
        Group aurora = new Group();
        aurora.setBlendMode(BlendMode.SCREEN);
        aurora.setEffect(new GaussianBlur(14));
        for (Curtain curtain : CURTAINS) {
            Paint rayPaint = new LinearGradient(0, 0, 0, 1, true, CycleMethod.NO_CYCLE,
                    new Stop(0, Color.TRANSPARENT),
                    new Stop(0.30, curtain.top().deriveColor(0, 1, 1, 0.22)),
                    new Stop(0.70, curtain.body().deriveColor(0, 1, 1, 0.55)),
                    new Stop(0.93, curtain.hem().deriveColor(0, 1, 1, 0.85)),
                    new Stop(1, Color.TRANSPARENT));
            Rectangle[] rays = new Rectangle[RAYS_PER_CURTAIN];
            for (int i = 0; i < rays.length; i++) {
                rays[i] = new Rectangle();
                rays[i].setFill(rayPaint);
                rays[i].setBlendMode(BlendMode.ADD);
            }
            curtainRays.add(rays);
            aurora.getChildren().addAll(rays);
        }

        // A rolling line of dark hills along the bottom
        hillHeights = new double[48];
        for (int i = 0; i < hillHeights.length; i++) {
            double roll = 0.5 + 0.5 * Math.sin(i * 0.45) * Math.cos(i * 0.17);
            hillHeights[i] = 0.06 + roll * 0.07 + random.nextDouble() * 0.015;
        }
        hills.setFill(Color.web("#010605"));

        shootingStar.setStroke(new LinearGradient(0, 0, 1, 0, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.TRANSPARENT), new Stop(1, Color.web("#ffffff", 0.9))));
        shootingStar.setStrokeWidth(1.6);
        shootingStar.setOpacity(0);
        motions.add(shootingStarFlight(random));

        getChildren().addAll(sky, starField, horizonGlow, aurora, shootingStar, hills);

        widthProperty().addListener((obs, old, w) -> placeStillParts());
        heightProperty().addListener((obs, old, h) -> placeStillParts());

        ripple = new AnimationTimer() {
            @Override
            public void handle(long now) {
                moveCurtains(now / 1_000_000_000.0);
            }
        };
        motions.forEach(Animation::play);
        ripple.start();
    }

    /** Stops every animation, for when the screen is being replaced. */
    public void stop() {
        ripple.stop();
        motions.forEach(Animation::stop);
    }

    /** Shapes each curtain for this moment: a wavy hem, with rays that rise and fade unevenly. */
    private void moveCurtains(double seconds) {
        double w = getWidth();
        double h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        for (int c = 0; c < CURTAINS.length; c++) {
            Curtain curtain = CURTAINS[c];
            Rectangle[] rays = curtainRays.get(c);
            double step = w * 1.2 / rays.length;
            for (int i = 0; i < rays.length; i++) {
                double x = -w * 0.1 + i * step;
                double u = x / w;
                double t = seconds * curtain.speed();
                // The hem sways in two overlapping waves, so it never looks regular
                double hem = h * (curtain.baseY()
                        + curtain.wave() * Math.sin(u * Math.PI * 2 * curtain.waveLength() + t * 2.1)
                        + curtain.wave() * 0.45 * Math.sin(u * Math.PI * 5.3 - t * 3.4 + c));
                // Rays brighten and stretch in bands that travel along the curtain
                double band = 0.5 + 0.5 * Math.sin(u * 23 + t * 6 + c * 1.7)
                        * Math.sin(u * 7.3 - t * 2.6);
                double flicker = 0.5 + 0.5 * Math.sin(seconds * 3.1 + i * 0.9 + c * 2.3);
                double rayHeight = h * curtain.height() * (0.55 + 0.6 * band);
                Rectangle ray = rays[i];
                ray.setX(x);
                ray.setY(hem - rayHeight);
                ray.setWidth(step * 1.5);
                ray.setHeight(rayHeight);
                ray.setOpacity(0.25 + 0.6 * band * (0.75 + 0.25 * flicker));
            }
        }
    }

    /** Places the stars and hills, which only change when the window is resized. */
    private void placeStillParts() {
        double w = getWidth();
        double h = getHeight();
        for (int i = 0; i < stars.size(); i++) {
            stars.get(i).setCenterX(starSpots[i][0] * w);
            stars.get(i).setCenterY(starSpots[i][1] * h);
        }
        List<Double> points = new ArrayList<>();
        points.add(0.0);
        points.add(h);
        for (int i = 0; i < hillHeights.length; i++) {
            points.add(w * i / (hillHeights.length - 1));
            points.add(h * (1 - hillHeights[i]));
        }
        points.add(w);
        points.add(h);
        hills.getPoints().setAll(points);
    }

    /** Every so often a star streaks down across the upper sky. */
    private Animation shootingStarFlight(Random random) {
        Timeline flight = new Timeline();
        flight.setCycleCount(Animation.INDEFINITE);
        flight.getKeyFrames().addAll(
                new KeyFrame(Duration.ZERO, e -> aimShootingStar(random),
                        new KeyValue(shootingStar.opacityProperty(), 0),
                        new KeyValue(shootingStar.translateXProperty(), 0),
                        new KeyValue(shootingStar.translateYProperty(), 0)),
                new KeyFrame(Duration.millis(150),
                        new KeyValue(shootingStar.opacityProperty(), 1)),
                new KeyFrame(Duration.millis(900),
                        new KeyValue(shootingStar.opacityProperty(), 0, Interpolator.EASE_OUT),
                        new KeyValue(shootingStar.translateXProperty(), 260, Interpolator.EASE_OUT),
                        new KeyValue(shootingStar.translateYProperty(), 110, Interpolator.EASE_OUT)),
                new KeyFrame(Duration.seconds(9)));
        flight.setDelay(Duration.seconds(3));
        return flight;
    }

    private void aimShootingStar(Random random) {
        double x = getWidth() * (0.05 + random.nextDouble() * 0.6);
        double y = getHeight() * (0.04 + random.nextDouble() * 0.2);
        shootingStar.setStartX(x - 90);
        shootingStar.setStartY(y - 38);
        shootingStar.setEndX(x);
        shootingStar.setEndY(y);
    }
}
