package org.jackhuang.hmcl.ui.dsh.skin.animation;

import javafx.util.Duration;
import org.jackhuang.hmcl.ui.dsh.skin.FunctionHelper;
import org.jackhuang.hmcl.ui.dsh.skin.SkinAnimation;
import org.jackhuang.hmcl.ui.dsh.skin.SkinCanvas;
import org.jackhuang.hmcl.ui.dsh.skin.SkinTransition;

public final class SkinAniWavingArms extends SkinAnimation {

    public SkinAniWavingArms(int weight, int time, double angle, SkinCanvas canvas) {
        SkinTransition larmTransition = new SkinTransition(Duration.millis(time), v -> v * angle,
                canvas.larm.getZRotate().angleProperty());

        SkinTransition rarmTransition = new SkinTransition(Duration.millis(time), v -> v * -angle,
                canvas.rarm.getZRotate().angleProperty());

        FunctionHelper.alwaysB(SkinTransition::setAutoReverse, true, larmTransition, rarmTransition);
        FunctionHelper.alwaysB(SkinTransition::setCycleCount, 2, larmTransition, rarmTransition);
        FunctionHelper.always(transitions::add, larmTransition, rarmTransition);
        this.weight = weight;
        init();
    }

}
