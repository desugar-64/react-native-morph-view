package com.morphview

import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.ViewManagerDelegate
import com.facebook.react.viewmanagers.MorphViewViewManagerInterface
import com.facebook.react.viewmanagers.MorphViewViewManagerDelegate

@ReactModule(name = MorphViewViewManager.NAME)
class MorphViewViewManager : SimpleViewManager<MorphViewView>(),
  MorphViewViewManagerInterface<MorphViewView> {
  private val mDelegate: ViewManagerDelegate<MorphViewView>

  init {
    mDelegate = MorphViewViewManagerDelegate(this)
  }

  override fun getDelegate(): ViewManagerDelegate<MorphViewView> = mDelegate

  override fun getName(): String = NAME

  public override fun createViewInstance(context: ThemedReactContext): MorphViewView =
    MorphViewView(context)

  override fun setFromUri(view: MorphViewView, value: String?) {
    view.setFromUri(value)
  }

  override fun setToUri(view: MorphViewView, value: String?) {
    view.setToUri(value)
  }

  override fun setToggle(view: MorphViewView, value: Boolean) {
    view.setToggle(value)
  }

  override fun setBlurRadius(view: MorphViewView, value: Float) {
    view.setBlurRadius(value)
  }

  override fun setDurationMs(view: MorphViewView, value: Float) {
    view.setDurationMs(value)
  }

  override fun setTintColor(view: MorphViewView, value: Int?) {
    view.setTintColorInt(value)
  }

  override fun setMorphBorderColor(view: MorphViewView, value: Int?) {
    view.setBorderColorInt(value)
  }

  override fun setMorphBorderWidth(view: MorphViewView, value: Float) {
    view.setBorderWidthPt(value)
  }

  companion object {
    const val NAME = "MorphViewView"
  }
}
