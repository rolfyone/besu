/*
 * Copyright ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.besu.ethereum.privacy;

import org.hyperledger.besu.enclave.Enclave;
import org.hyperledger.besu.enclave.types.PrivacyGroup;
import org.hyperledger.besu.enclave.types.PrivacyGroup.Type;
import org.hyperledger.besu.enclave.types.ReceiveResponse;
import org.hyperledger.besu.enclave.types.SendResponse;
import org.hyperledger.besu.ethereum.core.Account;
import org.hyperledger.besu.ethereum.core.Address;
import org.hyperledger.besu.ethereum.core.PrivacyParameters;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.mainnet.TransactionValidator;
import org.hyperledger.besu.ethereum.mainnet.ValidationResult;
import org.hyperledger.besu.ethereum.privacy.markertransaction.PrivateMarkerTransactionFactory;
import org.hyperledger.besu.ethereum.privacy.storage.PrivateStateStorage;
import org.hyperledger.besu.ethereum.rlp.BytesValueRLPOutput;
import org.hyperledger.besu.ethereum.transaction.CallParameter;
import org.hyperledger.besu.ethereum.worldstate.WorldStateArchive;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes;

public class DefaultPrivacyController implements PrivacyController {

  private static final Logger LOG = LogManager.getLogger();

  private final Enclave enclave;
  private final PrivateStateStorage privateStateStorage;
  private final WorldStateArchive privateWorldStateArchive;
  private final PrivateTransactionValidator privateTransactionValidator;
  private final PrivateMarkerTransactionFactory privateMarkerTransactionFactory;
  private final PrivateTransactionSimulator privateTransactionSimulator;

  public DefaultPrivacyController(
      final PrivacyParameters privacyParameters,
      final Optional<BigInteger> chainId,
      final PrivateMarkerTransactionFactory privateMarkerTransactionFactory,
      final PrivateTransactionSimulator privateTransactionSimulator) {
    this(
        privacyParameters.getEnclave(),
        privacyParameters.getPrivateStateStorage(),
        privacyParameters.getPrivateWorldStateArchive(),
        new PrivateTransactionValidator(chainId),
        privateMarkerTransactionFactory,
        privateTransactionSimulator);
  }

  public DefaultPrivacyController(
      final Enclave enclave,
      final PrivateStateStorage privateStateStorage,
      final WorldStateArchive privateWorldStateArchive,
      final PrivateTransactionValidator privateTransactionValidator,
      final PrivateMarkerTransactionFactory privateMarkerTransactionFactory,
      final PrivateTransactionSimulator privateTransactionSimulator) {
    this.enclave = enclave;
    this.privateStateStorage = privateStateStorage;
    this.privateWorldStateArchive = privateWorldStateArchive;
    this.privateTransactionValidator = privateTransactionValidator;
    this.privateMarkerTransactionFactory = privateMarkerTransactionFactory;
    this.privateTransactionSimulator = privateTransactionSimulator;
  }

  @Override
  public SendTransactionResponse sendTransaction(
      final PrivateTransaction privateTransaction, final String enclavePublicKey) {
    try {
      LOG.trace("Storing private transaction in enclave");
      final SendResponse sendResponse = sendRequest(privateTransaction, enclavePublicKey);
      final String enclaveKey = sendResponse.getKey();
      if (privateTransaction.getPrivacyGroupId().isPresent()) {
        final String privacyGroupId = privateTransaction.getPrivacyGroupId().get().toBase64String();
        return new SendTransactionResponse(enclaveKey, privacyGroupId);
      } else {
        final String privateFrom = privateTransaction.getPrivateFrom().toBase64String();
        final String privacyGroupId = getPrivacyGroupId(enclaveKey, privateFrom);
        return new SendTransactionResponse(enclaveKey, privacyGroupId);
      }
    } catch (final Exception e) {
      LOG.error("Failed to store private transaction in enclave", e);
      throw e;
    }
  }

  @Override
  public ReceiveResponse retrieveTransaction(
      final String enclaveKey, final String enclavePublicKey) {
    return enclave.receive(enclaveKey, enclavePublicKey);
  }

  @Override
  public PrivacyGroup createPrivacyGroup(
      final List<String> addresses,
      final String name,
      final String description,
      final String enclavePublicKey) {
    return enclave.createPrivacyGroup(addresses, enclavePublicKey, name, description);
  }

  @Override
  public String deletePrivacyGroup(final String privacyGroupId, final String enclavePublicKey) {
    return enclave.deletePrivacyGroup(privacyGroupId, enclavePublicKey);
  }

  @Override
  public PrivacyGroup[] findPrivacyGroup(
      final List<String> addresses, final String enclavePublicKey) {
    return enclave.findPrivacyGroup(addresses);
  }

  @Override
  public Transaction createPrivacyMarkerTransaction(
      final String transactionEnclaveKey, final PrivateTransaction privateTransaction) {
    return privateMarkerTransactionFactory.create(transactionEnclaveKey, privateTransaction);
  }

  @Override
  public ValidationResult<TransactionValidator.TransactionInvalidReason> validatePrivateTransaction(
      final PrivateTransaction privateTransaction,
      final String privacyGroupId,
      final String enclavePublicKey) {
    return privateTransactionValidator.validate(
        privateTransaction,
        determineBesuNonce(privateTransaction.getSender(), privacyGroupId, enclavePublicKey));
  }

  @Override
  public long determineEeaNonce(
      final String privateFrom,
      final String[] privateFor,
      final Address address,
      final String enclavePublicKey) {
    final List<String> groupMembers = Lists.asList(privateFrom, privateFor);

    final List<PrivacyGroup> matchingGroups =
        Lists.newArrayList(enclave.findPrivacyGroup(groupMembers));

    final List<PrivacyGroup> legacyGroups =
        matchingGroups.stream()
            .filter(group -> group.getType() == Type.LEGACY)
            .collect(Collectors.toList());

    if (legacyGroups.size() == 0) {
      // the legacy group does not exist yet
      return 0;
    }
    Preconditions.checkArgument(
        legacyGroups.size() == 1,
        String.format(
            "Found invalid number of privacy groups (%d), expected 1.", legacyGroups.size()));

    final String privacyGroupId = legacyGroups.get(0).getPrivacyGroupId();

    return determineBesuNonce(address, privacyGroupId, enclavePublicKey);
  }

  @Override
  public long determineBesuNonce(
      final Address sender, final String privacyGroupId, final String enclavePublicKey) {
    return privateStateStorage
        .getLatestStateRoot(Bytes.fromBase64String(privacyGroupId))
        .map(
            lastRootHash ->
                privateWorldStateArchive
                    .getMutable(lastRootHash)
                    .map(
                        worldState -> {
                          final Account maybePrivateSender = worldState.get(sender);

                          if (maybePrivateSender != null) {
                            return maybePrivateSender.getNonce();
                          }
                          // account has not interacted in this private state
                          return Account.DEFAULT_NONCE;
                        })
                    // private state does not exist
                    .orElse(Account.DEFAULT_NONCE))
        .orElse(
            // private state does not exist
            Account.DEFAULT_NONCE);
  }

  @Override
  public Optional<PrivateTransactionProcessor.Result> simulatePrivateTransaction(
      final String privacyGroupId,
      final String enclavePublicKey,
      final CallParameter callParams,
      final long blockNumber) {
    final Optional<PrivateTransactionProcessor.Result> result =
        privateTransactionSimulator.process(privacyGroupId, callParams, blockNumber);
    return result;
  }

  private SendResponse sendRequest(
      final PrivateTransaction privateTransaction, final String enclavePublicKey) {
    final BytesValueRLPOutput rlpOutput = new BytesValueRLPOutput();
    privateTransaction.writeTo(rlpOutput);
    final String payload = rlpOutput.encoded().toBase64String();

    if (privateTransaction.getPrivacyGroupId().isPresent()) {
      return enclave.send(
          payload, enclavePublicKey, privateTransaction.getPrivacyGroupId().get().toBase64String());
    } else {
      final List<String> privateFor =
          privateTransaction.getPrivateFor().get().stream()
              .map(Bytes::toBase64String)
              .collect(Collectors.toList());

      if (privateFor.isEmpty()) {
        privateFor.add(privateTransaction.getPrivateFrom().toBase64String());
      }
      return enclave.send(
          payload, privateTransaction.getPrivateFrom().toBase64String(), privateFor);
    }
  }

  private String getPrivacyGroupId(final String key, final String privateFrom) {
    LOG.debug("Getting privacy group for key {} and privateFrom {}", key, privateFrom);
    try {
      return enclave.receive(key, privateFrom).getPrivacyGroupId();
    } catch (final RuntimeException e) {
      LOG.error("Failed to retrieve private transaction in enclave", e);
      throw e;
    }
  }
}
