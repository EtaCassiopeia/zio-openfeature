package zio.openfeature.testkit

import java.util.concurrent.Phaser

/** A gate a waiter passes once it has been opened after the waiter took its ticket: an eventcount (generation counter)
  * on a [[java.util.concurrent.Phaser]].
  *
  * Take the ticket when the work starts and wait on it later. An [[open]] between the two still lets the waiter through
  * — the lost wakeup a set of latches opened "for whoever is parked now" suffers — while a waiter whose ticket
  * postdates the last [[open]] blocks until the next one. [[close]] lets every current and later waiter through for
  * good.
  */
final private[testkit] class Gate {

  // One registered party, so every `arrive` advances the phase and the phaser never terminates on its own.
  private val phaser = new Phaser(1)

  /** The current generation. Pass it to [[await]]. */
  def ticket(): Int = phaser.getPhase

  /** Let through every waiter holding a ticket taken before this call. */
  def open(): Unit = {
    phaser.arrive()
    ()
  }

  /** Block until the gate has been opened (or closed) since `ticket` was taken; returns at once if it already has.
    *
    * @throws InterruptedException
    *   if the waiting thread is interrupted
    */
  def await(ticket: Int): Unit = {
    phaser.awaitAdvanceInterruptibly(ticket)
    ()
  }

  /** Let every current and later waiter through. */
  def close(): Unit = phaser.forceTermination()
}
