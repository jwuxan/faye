package faye.ir;

/**
 * One IR form. Adding a form here without handling it in the codec and the engine fails
 * compilation, because both switch exhaustively over this interface.
 */
public sealed interface Node permits Seq, Step, Screen, Call, Hook, Choose, When, Retry, SetVar, Succeed, Fail {

    SourceRef source();
}
