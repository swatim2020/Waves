package com.wavesplatform.api.http

import io.swagger.annotations.{ApiModel, ApiModelProperty}

import scala.annotation.meta.field

object SwaggerDefinitions {
  @ApiModel("NxtConsensusData")
  class NxtConsensusDataDesc(
      @(ApiModelProperty @field)(name = "base-target", required = true)
      val baseTarget: Long,
      @(ApiModelProperty @field)(name = "generation-signature", required = true)
      val generationSignature: String
  )

  @ApiModel("BlockHeader")
  abstract class BlockHeaderDesc(
      val timestamp: Long,
      val version: Short,
      val height: Int,
      val totalFee: Int,
      val reference: String,
      val generator: String,
      val signature: String,
      @(ApiModelProperty @field)(name = "nxt-consensus", required = true)
      val consensusData: NxtConsensusDataDesc,
      val blocksize: Int,
      val transactionCount: Int,
      @(ApiModelProperty @field)(required = false, dataType = "[Ljava.lang.Short;")
      val features: Set[Short],
      @(ApiModelProperty @field)(required = false)
      val reward: Long,
      @(ApiModelProperty @field)(required = false)
      val desiredReward: Long
  )

  @ApiModel("Transaction")
  abstract class TransactionDesc(
      val id: String,
      @(ApiModelProperty @field)(required = false)
      val version: Short,
      val timestamp: Long,
      `type`: Short,
      @(ApiModelProperty @field)(required = false)
      val chainId: Short
  )

  @ApiModel("Block")
  abstract class BlockDesc(
      val transactions: List[TransactionDesc],
      timestamp: Long,
      version: Short,
      height: Int,
      totalFee: Int,
      reference: String,
      generator: String,
      signature: String,
      consensusData: NxtConsensusDataDesc,
      blocksize: Int,
      transactionCount: Int,
      features: Set[Short],
      reward: Long,
      desiredReward: Long
  ) extends BlockHeaderDesc(
        timestamp,
        version,
        height,
        totalFee,
        reference,
        generator,
        signature,
        consensusData,
        blocksize,
        transactionCount,
        features,
        reward,
        desiredReward
      )

  @ApiModel("Delay")
  case class DelayDesc(delay: Long)
  @ApiModel("Height")
  case class HeightDesc(height: Int)
  @ApiModel("Peer")
  case class PeerDesc(address: String, lastSeen: Long)

  @ApiModel("ConnectedPeer")
  case class ConnectedPeerDesc(
      address: String,
      declaredAddress: String,
      peerName: String,
      peerNonce: Long,
      applicationName: String,
      applicationVersion: String
  )

  @ApiModel("ConnectedPeers")
  case class ConnectedPeersDesc(peers: List[ConnectedPeerDesc])
  @ApiModel("ConnectionStatus")
  case class ConnectionStatusDesc(hostname: String, status: String)
  @ApiModel("BlacklistedPeer")
  case class BlacklistedPeerDesc(hostname: String, timestamp: Long, reason: String)
  @ApiModel("SuspendedPeer")
  case class SuspendedPeerDesc(hostname: String, timestamp: Long, reason: String)
  @ApiModel("Result")
  case class ResultDesc(result: String)

  @ApiModel("TransactionStatus")
  case class TransactionStatusDesc(
      id: String,
      @(ApiModelProperty @field)(required = true, allowableValues = "confirmed,unconfirmed,not_found")
      status: String,
      @(ApiModelProperty @field)(required = false)
      height: Int,
      @(ApiModelProperty @field)(required = false)
      confirmations: Int
  )

  @ApiModel("Size")
  case class SizeDesc(size: Int)
  @ApiModel("Fee")
  case class FeeDesc(feeAssetId: String, feeAmount: Long)
}