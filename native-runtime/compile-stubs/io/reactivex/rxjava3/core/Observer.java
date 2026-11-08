package io.reactivex.rxjava3.core;
public interface Observer {
    void onSubscribe(io.reactivex.rxjava3.disposables.Disposable disposable);
    void onNext(Object value);
    void onError(Throwable error);
    void onComplete();
}
