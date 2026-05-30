package com.morphview

import android.graphics.Color
import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.ViewManagerDelegate
import com.facebook.react.uimanager.annotations.ReactProp
import com.facebook.react.viewmanagers.MorphViewViewManagerInterface
import com.facebook.react.viewmanagers.MorphViewViewManagerDelegate

@ReactModule(name = MorphViewViewManager.NAME)
class MorphViewViewManager : SimpleViewManager<MorphViewView>(),
  MorphViewViewManagerInterface<MorphViewView> {
  private val mDelegate: ViewManagerDelegate<MorphViewView>

  init {
    mDelegate = MorphViewViewManagerDelegate(this)
  }

  override fun getDelegate(): ViewManagerDelegate<MorphViewView>? {
    return mDelegate
  }

  override fun getName(): String {
    return NAME
  }

  public override fun createViewInstance(context: ThemedReactContext): MorphViewView {
    return MorphViewView(context)
  }

  @ReactProp(name = "color")
  override fun setColor(view: MorphViewView?, color: Int?) {
    view?.setBackgroundColor(color ?: Color.TRANSPARENT)
  }

  companion object {
    const val NAME = "MorphViewView"
  }
}
