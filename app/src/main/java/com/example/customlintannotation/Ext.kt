package com.example.customlintannotation

import android.view.View
import com.example.customlint.CheckArgsAndResult
import io.reactivex.Observable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import io.reactivex.rxkotlin.addTo
import java.util.concurrent.TimeUnit

/**
 * Created by mick on 2019-08-14.
 */
@CheckArgsAndResult
inline fun <reified T : View> T.setRxOnClick(
    compositeDisposable: CompositeDisposable? = null,
    crossinline onClick: (T) -> Unit
): Disposable {
    return Observable.create<T> { e -> setOnClickListener { e.onNext(it as T) } }
        .throttleFirst(500L, TimeUnit.MILLISECONDS)
        .doOnNext { onClick(it) }
        .subscribe()
        .run { compositeDisposable?.let { addTo(it) } ?: this }
}