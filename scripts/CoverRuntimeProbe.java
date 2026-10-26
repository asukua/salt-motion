import androidx.compose.ui.Modifier;
import androidx.compose.ui.draw.DrawModifierKt;
import androidx.compose.ui.graphics.GraphicsLayerModifierKt;
import kotlin.Unit;
import java.util.*;

/** Verify the returned host modifier itself changes, without relying on Hook return write-back. */
public final class CoverRuntimeProbe {
    public static void verify(ClassLoader loader, Object runtime) throws Exception {
        var runtimeType = loader.loadClass("dev.saltmotion.MotionRuntime");
        var elementType = loader.loadClass("dev.saltmotion.ui.MotionCoverElement");
        var bridgeType = loader.loadClass("dev.saltmotion.hook.CoverModifier");
        var bridge = bridgeType.getField("INSTANCE").get(null);
        var attach = bridgeType.getMethod("attach", Modifier.class, Modifier.class, Modifier.Element.class, boolean.class);
        for (boolean square : new boolean[]{true, false}) {
            var element = (Modifier.Element) elementType.getConstructor(runtimeType).newInstance(runtime);
            Modifier receiver = DrawModifierKt.drawWithContent(Modifier.Companion, scope -> Unit.INSTANCE);
            var before = flatten(receiver);
            Modifier result = square
                ? DrawModifierKt.drawWithCache(receiver, scope -> scope.onDrawWithContent(draw -> Unit.INSTANCE))
                : GraphicsLayerModifierKt.graphicsLayer(receiver, scope -> Unit.INSTANCE);
            Modifier retained = result;
            if ((boolean) attach.invoke(bridge, result, Modifier.Companion, element, square))
                throw new AssertionError("Unrelated receiver was modified");
            if ((boolean) attach.invoke(bridge, result, receiver, element, !square))
                throw new AssertionError("Wrong native leaf type was modified");
            if (!(boolean) attach.invoke(bridge, result, receiver, element, square))
                throw new AssertionError("Verified fresh cover modifier rejected");
            if (result != retained || flatten(retained).getLast() != element)
                throw new AssertionError("The original return object must contain the animation element");
            if (!flatten(receiver).equals(before))
                throw new AssertionError("The existing receiver chain was modified");
            if ((boolean) attach.invoke(bridge, result, receiver, element, square))
                throw new AssertionError("An already-connected chain was modified again");
            var node = elementType.getMethod("create").invoke(element);
            if (!node.getClass().getName().equals("dev.saltmotion.ui.MotionCoverNode"))
                throw new AssertionError("Unexpected native modifier node");
            System.out.println("PASS real host modifier square=" + square + "; retained return identity, connected node, unchanged receiver, rejected unrelated/repeated attachment");
        }
    }

    private static List<Modifier.Element> flatten(Modifier value) {
        return value.foldIn(new ArrayList<Modifier.Element>(), (items, element) -> { items.add(element); return items; });
    }
}
