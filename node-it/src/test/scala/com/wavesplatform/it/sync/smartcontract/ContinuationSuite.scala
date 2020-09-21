package com.wavesplatform.it.sync.smartcontract

import com.typesafe.config.Config
import com.wavesplatform.account.KeyPair
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.{Base58, EitherExt2}
import com.wavesplatform.features.BlockchainFeatures
import com.wavesplatform.it.NodeConfigs
import com.wavesplatform.it.NodeConfigs.Default
import com.wavesplatform.it.api.DebugStateChanges
import com.wavesplatform.it.api.SyncHttpApi._
import com.wavesplatform.it.sync._
import com.wavesplatform.it.transactions.BaseTransactionSuite
import com.wavesplatform.it.util._
import com.wavesplatform.lang.v1.FunctionHeader
import com.wavesplatform.lang.v1.compiler.Terms
import com.wavesplatform.lang.v1.estimator.ScriptEstimator
import com.wavesplatform.state._
import com.wavesplatform.transaction.Asset.Waves
import com.wavesplatform.transaction.TxVersion
import com.wavesplatform.transaction.smart.ContinuationTransaction
import com.wavesplatform.transaction.smart.InvokeScriptTransaction.Payment
import com.wavesplatform.transaction.smart.script.ScriptCompiler
import monix.eval.Coeval
import org.scalatest.{Assertion, OptionValues}

class ContinuationSuite extends BaseTransactionSuite with OptionValues {
  private val activationHeight = 5

  override protected def nodeConfigs: Seq[Config] =
    NodeConfigs
      .Builder(Default, 2, Seq.empty)
      .overrideBase(_.preactivatedFeatures((BlockchainFeatures.ContinuationTransaction.id, activationHeight)))
      .overrideBase(_.raw("waves.blockchain.use-evaluator-v2 = true"))
      .buildNonConflicting()

  private lazy val dApp: KeyPair   = firstKeyPair
  private lazy val caller: KeyPair = secondKeyPair

  private val dummyEstimator = new ScriptEstimator {
    override val version: Int = 0

    override def apply(
        declaredVals: Set[String],
        functionCosts: Map[FunctionHeader, Coeval[Long]],
        expr: Terms.EXPR
    ): Either[String, Long] = Right(1)
  }

  private val script = {
    val scriptText =
      s"""
         |{-# STDLIB_VERSION 4 #-}
         |{-# CONTENT_TYPE DAPP #-}
         |
         | @Callable(inv)
         | func foo() = {
         |  let a =
         |    height == $activationHeight                                                  &&
         |    getInteger(Address(base58''), "key") == unit                                 &&
         |    !(${List.fill(150)("sigVerify(base64'',base64'',base64'')").mkString("||")}) &&
         |    height == $activationHeight
         |  [BooleanEntry("a", a), BinaryEntry("sender", inv.caller.bytes)]
         | }
         |
       """.stripMargin
    ScriptCompiler.compile(scriptText, dummyEstimator).explicitGet()._1.bytes().base64
  }

  test("can't set continuation before activation") {
    assertBadRequestAndMessage(
      sender.setScript(dApp, Some(script), setScriptFee),
      "State check failed. Reason: Contract function (foo) is too complex: 30638 > 4000"
    )
  }

  test("can set continuation after activation") {
    nodes.waitForHeight(activationHeight)
    sender.setScript(dApp, Some(script), setScriptFee, waitForTx = true).id

    val scriptInfo = sender.addressScriptInfo(dApp.toAddress.toString)
    scriptInfo.script.isEmpty shouldBe false
    scriptInfo.scriptText.isEmpty shouldBe false
    scriptInfo.script.get.startsWith("base64:") shouldBe true
  }

  test("successful continuation") {
    val invoke = sender.invokeScript(
      caller,
      dApp.toAddress.toString,
      func = Some("foo"),
      args = Nil,
      payment = Seq(Payment(1.waves, Waves)),
      fee = 1.waves,
      version = TxVersion.V2,
      waitForTx = true
    )
    waitForContinuation(invoke._1.id)
    checkContinuationChain(invoke._1.id, sender.height)
    nodes.foreach { node =>
      node.getDataByKey(dApp.toAddress.toString, "a") shouldBe BooleanDataEntry("a", true)
      node.getDataByKey(dApp.toAddress.toString, "sender") shouldBe BinaryDataEntry("sender", ByteStr(Base58.decode(caller.toAddress.toString)))
    }
  }

  ignore("forbid transactions from DApp address until continuation is completed") {
    val invoke = sender.invokeScript(
      caller,
      dApp.toAddress.toString,
      func = Some("foo"),
      args = Nil,
      fee = 1.waves,
      version = TxVersion.V2,
      waitForTx = true
    )

    assertTransactionsSendError(dApp)
    nodes.waitForHeight(sender.height + 1)
    assertTransactionsSendError(dApp)
    nodes.waitForHeight(sender.height + 1)
    assertTransactionsSendError(dApp)

    waitForContinuation(invoke._1.id)
    assertTransactionsSendSuccess(dApp)
  }

  ignore("don't forbid transactions from other addresses while continuation is not completed") {
    val invoke = sender.invokeScript(
      caller,
      dApp.toAddress.toString,
      func = Some("foo"),
      args = Nil,
      fee = 1.waves,
      version = TxVersion.V2,
      waitForTx = true
    )

    assertTransactionsSendSuccess(caller)
    nodes.waitForHeight(sender.height + 1)
    assertTransactionsSendSuccess(caller)

    waitForContinuation(invoke._1.id)
    assertTransactionsSendSuccess(caller)
  }

  test("insufficient fee") {
    lazy val invokeScriptTx = sender.invokeScript(
      caller,
      dApp.toAddress.toString,
      func = Some("foo"),
      args = Nil,
      payment = Seq(Payment(1.waves, Waves)),
      fee = invokeFee,
      version = TxVersion.V2
    )

    assertBadRequestAndMessage(
      invokeScriptTx,
      "Fee in WAVES for InvokeScriptTransaction (900000 in WAVES) " +
        "with 8 invocation steps " +
        "does not exceed minimal value of 4000000 WAVES."
    )
  }

  private def waitForContinuation(invokeId: String): Boolean = {
    nodes.waitFor(
      s"chain of continuation for InvokeScript Transaction with id = $invokeId is completed"
    )(
       _.blockSeq(sender.height - 1, sender.height)
        .flatMap(_.transactions)
        .exists { tx =>
          tx._type == ContinuationTransaction.typeId &&
          tx.applicationStatus.contains("succeeded") &&
          tx.invokeScriptTransactionId.contains(invokeId)
        }
    )(
      _.forall(identity)
    )
  }

  private def checkContinuationChain(invokeId: String, completionHeight: Int): Assertion = {
    val invoke = sender.transactionInfo[DebugStateChanges](invokeId)
    val continuations =
      sender.blockSeq(invoke.height, completionHeight)
        .flatMap(_.transactions)
        .filter(tx => tx._type == ContinuationTransaction.typeId && tx.invokeScriptTransactionId.contains(invokeId))

    invoke.applicationStatus.value shouldBe "script_execution_in_progress"
    continuations.dropRight(1).foreach(_.applicationStatus.value shouldBe "script_execution_in_progress")
    continuations.last.applicationStatus.value shouldBe "succeeded"
    continuations.map(_.nonce.value) shouldBe continuations.indices
    invoke.timestamp +: continuations.map(_.timestamp) shouldBe sorted
  }

  private def assertTransactionsSendError(txSender: KeyPair): Assertion = {
    assertBadRequestAndMessage(
      putData(txSender),
      "Can't process transaction from the address from which DApp is executing"
    )
    assertBadRequestAndMessage(
      transfer(txSender),
      "Can't process transaction from the address from which DApp is executing"
    )
    assertBadRequestAndMessage(
      createAlias(txSender),
      "Can't process transaction from the address from which DApp is executing"
    )
  }

  private def assertTransactionsSendSuccess(txSender: KeyPair) = {
    putData(txSender)
    transfer(txSender)
    createAlias(txSender)
  }

  private def transfer(txSender: KeyPair) =
    sender.transfer(txSender, thirdAddress, amount = 1, smartMinFee, waitForTx = true)

  private def createAlias(txSender: KeyPair) =
    sender.createAlias(txSender, s"alias${System.currentTimeMillis()}", smartMinFee, waitForTx = true)

  private def putData(txSender: KeyPair) = {
    val data = List(StringDataEntry("key", "value"))
    sender.putData(txSender, data, calcDataFee(data, TxVersion.V1) + smartFee, waitForTx = true)
  }
}
