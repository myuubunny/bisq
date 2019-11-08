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

package bisq.network.p2p.storage.payload;

import bisq.common.crypto.Sig;
import bisq.common.proto.network.NetworkProtoResolver;
import bisq.common.util.Utilities;

import com.google.protobuf.ByteString;

import java.security.PublicKey;

import java.time.Clock;

import lombok.EqualsAndHashCode;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@EqualsAndHashCode(callSuper = true)
@Value
public class ProtectedMailboxStorageEntry extends ProtectedStorageEntry {
    private final byte[] receiversPubKeyBytes;
    transient private PublicKey receiversPubKey;

    public ProtectedMailboxStorageEntry(MailboxStoragePayload mailboxStoragePayload,
                                        PublicKey ownerPubKey,
                                        int sequenceNumber,
                                        byte[] signature,
                                        PublicKey receiversPubKey,
                                        Clock clock) {
        this(mailboxStoragePayload,
                Sig.getPublicKeyBytes(ownerPubKey),
                ownerPubKey,
                sequenceNumber,
                signature,
                Sig.getPublicKeyBytes(receiversPubKey),
                receiversPubKey,
                clock.millis(),
                clock);
    }

    private ProtectedMailboxStorageEntry(MailboxStoragePayload mailboxStoragePayload,
                                        byte[] ownerPubKeyBytes,
                                        PublicKey ownerPubKey,
                                        int sequenceNumber,
                                        byte[] signature,
                                        byte[] receiversPubKeyBytes,
                                        PublicKey receiversPubKey,
                                        long creationTimeStamp,
                                        Clock clock) {
        super(mailboxStoragePayload,
                ownerPubKeyBytes,
                ownerPubKey,
                sequenceNumber,
                signature,
                creationTimeStamp,
                clock);

        this.receiversPubKey = receiversPubKey;
        this.receiversPubKeyBytes = receiversPubKeyBytes;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public MailboxStoragePayload getMailboxStoragePayload() {
        return (MailboxStoragePayload) getProtectedStoragePayload();
    }

    /*
     * Returns true if this Entry is valid for an add operation. For mailbox Entrys, the entry owner must
     * match the valid sender Public Key specified in the payload. (Only sender can add)
     */
    @Override
    public boolean isValidForAddOperation() {
        if (!this.isSignatureValid())
            return false;

        MailboxStoragePayload mailboxStoragePayload = this.getMailboxStoragePayload();
        boolean result = mailboxStoragePayload.getSenderPubKeyForAddOperation() != null &&
                mailboxStoragePayload.getSenderPubKeyForAddOperation().equals(this.getOwnerPubKey());

        if (!result) {
            String res1 = this.toString();
            String res2 = "null";
            if (mailboxStoragePayload != null && mailboxStoragePayload.getOwnerPubKey() != null)
                res2 = Utilities.encodeToHex(mailboxStoragePayload.getSenderPubKeyForAddOperation().getEncoded(),true);

            log.warn(String.format("ProtectedMailboxStorageEntry::isValidForAddOperation() failed. " +
                    "Entry owner does not match sender key in payload:\nProtectedStorageEntry=%s\n" +
                    "SenderPubKeyForAddOperation=%s", res1, res2));
        }

        return result;
    }

    /*
     * Returns true if the Entry is valid for a remove operation. For mailbox Entrys, the entry owner must
     * match the payload owner. (Only receiver can remove)
     */
    @Override
    public boolean isValidForRemoveOperation() {
        if (!this.isSignatureValid())
            return false;

        MailboxStoragePayload mailboxStoragePayload = this.getMailboxStoragePayload();
        boolean result = mailboxStoragePayload.getOwnerPubKey() != null &&
                mailboxStoragePayload.getOwnerPubKey().equals(this.getOwnerPubKey());

        if (!result) {
            String res1 = this.toString();
            String res2 = "null";
            if (mailboxStoragePayload != null && mailboxStoragePayload.getOwnerPubKey() != null)
                res2 = Utilities.encodeToHex(mailboxStoragePayload.getOwnerPubKey().getEncoded(), true);

            log.warn(String.format("ProtectedMailboxStorageEntry::isValidForRemoveOperation() failed. " +
                    "Entry owner does not match Payload owner:\nProtectedStorageEntry=%s\n" +
                    "PayloadOwner=%s", res1, res2));
        }

        return result;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    private ProtectedMailboxStorageEntry(MailboxStoragePayload mailboxStoragePayload,
                                         byte[] ownerPubKeyBytes,
                                         int sequenceNumber,
                                         byte[] signature,
                                         byte[] receiversPubKeyBytes,
                                         long creationTimeStamp,
                                         Clock clock) {
        this(mailboxStoragePayload,
                ownerPubKeyBytes,
                Sig.getPublicKeyFromBytes(ownerPubKeyBytes),
                sequenceNumber,
                signature,
                receiversPubKeyBytes,
                Sig.getPublicKeyFromBytes(receiversPubKeyBytes),
                creationTimeStamp,
                clock);
    }

    public protobuf.ProtectedMailboxStorageEntry toProtoMessage() {
        return protobuf.ProtectedMailboxStorageEntry.newBuilder()
                .setEntry((protobuf.ProtectedStorageEntry) super.toProtoMessage())
                .setReceiversPubKeyBytes(ByteString.copyFrom(receiversPubKeyBytes))
                .build();
    }

    public static ProtectedMailboxStorageEntry fromProto(protobuf.ProtectedMailboxStorageEntry proto,
                                                         NetworkProtoResolver resolver) {
        ProtectedStorageEntry entry = ProtectedStorageEntry.fromProto(proto.getEntry(), resolver);
        return new ProtectedMailboxStorageEntry(
                (MailboxStoragePayload) entry.getProtectedStoragePayload(),
                entry.getOwnerPubKey().getEncoded(),
                entry.getSequenceNumber(),
                entry.getSignature(),
                proto.getReceiversPubKeyBytes().toByteArray(),
                entry.getCreationTimeStamp(),
                resolver.getClock());
    }


    @Override
    public String toString() {
        return "ProtectedMailboxStorageEntry{" +
                "\n\tReceivers Public Key:    " + Utilities.bytesAsHexString(receiversPubKeyBytes) +
                "\n" + super.toString();
    }
}
