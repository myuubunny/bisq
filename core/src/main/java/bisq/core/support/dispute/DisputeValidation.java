/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.core.support.dispute;

import bisq.core.dao.DaoFacade;
import bisq.core.support.SupportType;
import bisq.core.util.validation.RegexValidatorFactory;

import bisq.network.p2p.NodeAddress;

import bisq.common.config.Config;
import bisq.common.util.Tuple3;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionOutput;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

@Slf4j
public class DisputeValidation {

    public static void validateNodeAddresses(Dispute dispute, Config config)
            throws NodeAddressException {
        if (!config.useLocalhostForP2P) {
            validateNodeAddress(dispute, dispute.getContract().getBuyerNodeAddress());
            validateNodeAddress(dispute, dispute.getContract().getSellerNodeAddress());
        }
    }

    private static void validateNodeAddress(Dispute dispute, NodeAddress nodeAddress) throws NodeAddressException {
        if (!RegexValidatorFactory.onionAddressRegexValidator().validate(nodeAddress.getFullAddress()).isValid) {
            String msg = "Node address " + nodeAddress.getFullAddress() + " at dispute with trade ID " +
                    dispute.getShortTradeId() + " is not a valid address";
            log.error(msg);
            throw new NodeAddressException(dispute, msg);
        }
    }

    public static void validateDonationAddressMatchesAnyPastParamValues(Dispute dispute,
                                                                        String addressAsString,
                                                                        DaoFacade daoFacade)
            throws AddressException {
        Set<String> allPastParamValues = daoFacade.getAllDonationAddresses();
        if (!allPastParamValues.contains(addressAsString)) {
            String errorMsg = "Donation address is not a valid DAO donation address." +
                    "\nAddress used in the dispute: " + addressAsString +
                    "\nAll DAO param donation addresses:" + allPastParamValues;
            log.error(errorMsg);
            throw new AddressException(dispute, errorMsg);
        }
    }

    public static void validateDonationAddress(Dispute dispute,
                                               Transaction delayedPayoutTx,
                                               NetworkParameters params,
                                               DaoFacade daoFacade
    )
            throws AddressException {
        TransactionOutput output = delayedPayoutTx.getOutput(0);
        Address address = output.getScriptPubKey().getToAddress(params);
        if (address == null) {
            String errorMsg = "Donation address cannot be resolved (not of type P2PK nor P2SH nor P2WH). Output: " + output;
            log.error(errorMsg);
            log.error(delayedPayoutTx.toString());
            throw new DisputeValidation.AddressException(dispute, errorMsg);
        }
        String delayedPayoutTxOutputAddress = address.toString();
        validateDonationAddressMatchesAnyPastParamValues(dispute, delayedPayoutTxOutputAddress, daoFacade);

        // Verify that address in the dispute matches the one in the trade.
        checkArgument(delayedPayoutTxOutputAddress.equals(dispute.getDonationAddressOfDelayedPayoutTx()),
                "donationAddressOfDelayedPayoutTx from dispute does not match address from delayed payout tx");
    }

    public static void testIfAnyDisputeTriedReplay(List<Dispute> disputeList,
                                                   Consumer<DisputeReplayException> exceptionHandler) {
        var tuple = getTestReplayHashMaps(disputeList);
        Map<String, Set<String>> disputesPerTradeId = tuple.first;
        Map<String, Set<String>> disputesPerDelayedPayoutTxId = tuple.second;
        Map<String, Set<String>> disputesPerDepositTxId = tuple.third;

        disputeList.forEach(disputeToTest -> {
            try {
                testIfDisputeTriesReplay(disputeToTest,
                        disputesPerTradeId,
                        disputesPerDelayedPayoutTxId,
                        disputesPerDepositTxId);

            } catch (DisputeReplayException e) {
                exceptionHandler.accept(e);
            }
        });
    }

    public static void testIfDisputeTriesReplay(Dispute dispute,
                                                List<Dispute> disputeList) throws DisputeReplayException {
        var tuple = getTestReplayHashMaps(disputeList);
        Map<String, Set<String>> disputesPerTradeId = tuple.first;
        Map<String, Set<String>> disputesPerDelayedPayoutTxId = tuple.second;
        Map<String, Set<String>> disputesPerDepositTxId = tuple.third;

        testIfDisputeTriesReplay(dispute,
                disputesPerTradeId,
                disputesPerDelayedPayoutTxId,
                disputesPerDepositTxId);
    }

    private static Tuple3<Map<String, Set<String>>, Map<String, Set<String>>, Map<String, Set<String>>> getTestReplayHashMaps(
            List<Dispute> disputeList) {
        Map<String, Set<String>> disputesPerTradeId = new HashMap<>();
        Map<String, Set<String>> disputesPerDelayedPayoutTxId = new HashMap<>();
        Map<String, Set<String>> disputesPerDepositTxId = new HashMap<>();
        disputeList.forEach(dispute -> {
            String uid = dispute.getUid();

            String tradeId = dispute.getTradeId();
            disputesPerTradeId.putIfAbsent(tradeId, new HashSet<>());
            Set<String> set = disputesPerTradeId.get(tradeId);
            set.add(uid);

            String delayedPayoutTxId = dispute.getDelayedPayoutTxId();
            if (delayedPayoutTxId != null) {
                disputesPerDelayedPayoutTxId.putIfAbsent(delayedPayoutTxId, new HashSet<>());
                set = disputesPerDelayedPayoutTxId.get(delayedPayoutTxId);
                set.add(uid);
            }

            String depositTxId = dispute.getDepositTxId();
            if (depositTxId != null) {
                disputesPerDepositTxId.putIfAbsent(depositTxId, new HashSet<>());
                set = disputesPerDepositTxId.get(depositTxId);
                set.add(uid);
            }
        });

        return new Tuple3<>(disputesPerTradeId, disputesPerDelayedPayoutTxId, disputesPerDepositTxId);
    }

    private static void testIfDisputeTriesReplay(Dispute disputeToTest,
                                                 Map<String, Set<String>> disputesPerTradeId,
                                                 Map<String, Set<String>> disputesPerDelayedPayoutTxId,
                                                 Map<String, Set<String>> disputesPerDepositTxId)
            throws DisputeReplayException {

        try {
            String disputeToTestTradeId = disputeToTest.getTradeId();
            String disputeToTestDelayedPayoutTxId = disputeToTest.getDelayedPayoutTxId();
            String disputeToTestDepositTxId = disputeToTest.getDepositTxId();
            String disputeToTestUid = disputeToTest.getUid();

            // For pre v1.4.0 we do not get the delayed payout tx sent in mediation cases but in refund agent case we do.
            // So until all users have updated to 1.4.0 we only check in refund agent case. With 1.4.0 we send the
            // delayed payout tx also in mediation cases and that if check can be removed.
            if (disputeToTest.getSupportType() == SupportType.REFUND) {
                checkNotNull(disputeToTestDelayedPayoutTxId,
                        "Delayed payout transaction ID is null. " +
                                "Trade ID: " + disputeToTestTradeId);
            }
            checkNotNull(disputeToTestDepositTxId,
                    "depositTxId must not be null. Trade ID: " + disputeToTestTradeId);
            checkNotNull(disputeToTestUid,
                    "agentsUid must not be null. Trade ID: " + disputeToTestTradeId);

            Set<String> disputesPerTradeIdItems = disputesPerTradeId.get(disputeToTestTradeId);
            checkArgument(disputesPerTradeIdItems != null && disputesPerTradeIdItems.size() <= 2,
                    "We found more then 2 disputes with the same trade ID. " +
                            "Trade ID: " + disputeToTestTradeId);
            if (!disputesPerDelayedPayoutTxId.isEmpty()) {
                Set<String> disputesPerDelayedPayoutTxIdItems = disputesPerDelayedPayoutTxId.get(disputeToTestDelayedPayoutTxId);
                checkArgument(disputesPerDelayedPayoutTxIdItems != null && disputesPerDelayedPayoutTxIdItems.size() <= 2,
                        "We found more then 2 disputes with the same delayedPayoutTxId. " +
                                "Trade ID: " + disputeToTestTradeId);
            }
            if (!disputesPerDepositTxId.isEmpty()) {
                Set<String> disputesPerDepositTxIdItems = disputesPerDepositTxId.get(disputeToTestDepositTxId);
                checkArgument(disputesPerDepositTxIdItems != null && disputesPerDepositTxIdItems.size() <= 2,
                        "We found more then 2 disputes with the same depositTxId. " +
                                "Trade ID: " + disputeToTestTradeId);
            }
        } catch (IllegalArgumentException e) {
            throw new DisputeReplayException(disputeToTest, e.getMessage());
        } catch (NullPointerException e) {
            log.error("NullPointerException at testIfDisputeTriesReplay: " +
                            "disputeToTest={}, disputesPerTradeId={}, disputesPerDelayedPayoutTxId={}, " +
                            "disputesPerDepositTxId={}",
                    disputeToTest, disputesPerTradeId, disputesPerDelayedPayoutTxId, disputesPerDepositTxId);
            throw new DisputeReplayException(disputeToTest, e.toString() + " at dispute " + disputeToTest.toString());
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Exceptions
    ///////////////////////////////////////////////////////////////////////////////////////////

    public static class ValidationException extends Exception {
        @Getter
        private final Dispute dispute;

        public ValidationException(Dispute dispute, String msg) {
            super(msg);
            this.dispute = dispute;
        }
    }

    public static class NodeAddressException extends ValidationException {
        public NodeAddressException(Dispute dispute, String msg) {
            super(dispute, msg);
        }
    }

    public static class AddressException extends ValidationException {
        public AddressException(Dispute dispute, String msg) {
            super(dispute, msg);
        }
    }

    public static class DisputeReplayException extends ValidationException {
        public DisputeReplayException(Dispute dispute, String msg) {
            super(dispute, msg);
        }
    }
}