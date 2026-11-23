package com.example.budgetapp.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 「计划」页的根容器：在整个页面上识别左右滑动，用于切换月份。
 *
 * <p>为什么不用 View 的 OnTouchListener：列表为空时页面只剩一堆不可点击的
 * View，触摸事件会一路穿透、不一定回到挂监听的那个 View 上，导致空月份滑不动。
 * 这里直接覆写 dispatchTouchEvent，事件从最外层进入时先喂给手势识别器，
 * 再正常分发给子 View，所以无论手指落在列表、空白、汇总卡还是标题上都能滑。
 */
public class SwipeMonthLayout extends FrameLayout {

    /** 回调：direction = -1 上一个月，1 下一个月。 */
    public interface OnMonthSwipeListener {
        void onMonthSwipe(int direction);
    }

    private final GestureDetector detector;
    private final int minDistancePx;
    private OnMonthSwipeListener listener;

    public SwipeMonthLayout(@NonNull Context context) {
        this(context, null);
    }

    public SwipeMonthLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SwipeMonthLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        minDistancePx = (int) (56 * getResources().getDisplayMetrics().density);
        detector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(@NonNull MotionEvent e) {
                return true;
            }

            @Override
            public boolean onFling(@Nullable MotionEvent e1, @NonNull MotionEvent e2, float velocityX, float velocityY) {
                if (e1 == null || listener == null) return false;
                float diffX = e2.getX() - e1.getX();
                float diffY = e2.getY() - e1.getY();
                if (Math.abs(diffX) > Math.abs(diffY) * 1.5f
                        && Math.abs(diffX) > minDistancePx
                        && Math.abs(velocityX) > 200) {
                    listener.onMonthSwipe(diffX > 0 ? -1 : 1);
                    return true;
                }
                return false;
            }
        });
    }

    public void setOnMonthSwipeListener(@Nullable OnMonthSwipeListener listener) {
        this.listener = listener;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        detector.onTouchEvent(ev);
        return super.dispatchTouchEvent(ev);
    }
}
