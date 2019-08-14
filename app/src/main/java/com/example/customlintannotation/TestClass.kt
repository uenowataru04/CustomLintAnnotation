package com.example.customlintannotation

import android.widget.ImageView
import android.widget.TextView
import io.reactivex.disposables.CompositeDisposable

/**
 * Created by mick on 2019-08-14.
 */
class TestClass(
    private val textView: TextView,
    private val imageView: ImageView
) {
    private val cd = CompositeDisposable()

    fun initialize() {
        textView.setRxOnClick {

        }

        imageView.setRxOnClick(cd) {
        }
    }
}