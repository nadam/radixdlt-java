package com.radixdlt.client.application;

import com.radixdlt.client.application.actions.DataStore;
import com.radixdlt.client.application.actions.TokenTransfer;
import com.radixdlt.client.application.objects.Data;
import com.radixdlt.client.application.objects.UnencryptedData;
import com.radixdlt.client.application.translate.DataStoreTranslator;
import com.radixdlt.client.application.translate.TokenTransferTranslator;
import com.radixdlt.client.assets.Amount;
import com.radixdlt.client.assets.Asset;
import com.radixdlt.client.core.RadixUniverse;
import com.radixdlt.client.core.address.RadixAddress;
import com.radixdlt.client.core.atoms.ApplicationPayloadAtom;
import com.radixdlt.client.core.atoms.AtomBuilder;
import com.radixdlt.client.core.atoms.Consumable;
import com.radixdlt.client.core.atoms.TransactionAtom;
import com.radixdlt.client.application.identity.RadixIdentity;
import com.radixdlt.client.core.ledger.RadixLedger;
import com.radixdlt.client.core.network.AtomSubmissionUpdate;
import com.radixdlt.client.core.network.AtomSubmissionUpdate.AtomSubmissionState;
import com.radixdlt.client.application.translate.ConsumableDataSource;
import com.radixdlt.client.application.translate.TransactionAtoms;
import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.observables.ConnectableObservable;
import java.util.Collection;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The Radix Dapp API, a high level api which dapps can utilize. The class hides
 * the complexity of Atoms and cryptography and exposes a simple high level interface.
 */
public class RadixApplicationAPI {
	public static class Result {
		private final Observable<AtomSubmissionUpdate> updates;
		private final Completable completable;

		private Result(Observable<AtomSubmissionUpdate> updates) {
			this.updates = updates;

			this.completable = updates.filter(AtomSubmissionUpdate::isComplete)
				.firstOrError()
				.flatMapCompletable(update -> {
					if (update.getState() == AtomSubmissionState.STORED) {
						return Completable.complete();
					} else {
						return Completable.error(new RuntimeException(update.getMessage()));
					}
				});
		}

		public Observable<AtomSubmissionUpdate> toObservable() {
			return updates;
		}

		public Completable toCompletable() {
			return completable;
		}
	}


	private final RadixIdentity identity;
	private final RadixLedger ledger;
	private final DataStoreTranslator dataStoreTranslator;
	private final TokenTransferTranslator tokenTransferTranslator;
	private final Supplier<AtomBuilder> atomBuilderSupplier;
	private final ConsumableDataSource consumableDataSource;

	private RadixApplicationAPI(RadixIdentity identity, RadixUniverse universe, Supplier<AtomBuilder> atomBuilderSupplier) {
		this.identity = identity;
		this.ledger = universe.getLedger();
		this.consumableDataSource = new ConsumableDataSource(ledger);
		this.dataStoreTranslator = DataStoreTranslator.getInstance();
		this.tokenTransferTranslator = new TokenTransferTranslator(universe, consumableDataSource);
		this.atomBuilderSupplier = atomBuilderSupplier;
	}

	public static RadixApplicationAPI create(RadixIdentity identity) {
		Objects.requireNonNull(identity);
		return create(identity, RadixUniverse.getInstance(), AtomBuilder::new);
	}

	public static RadixApplicationAPI create(RadixIdentity identity, RadixUniverse universe, Supplier<AtomBuilder> atomBuilderSupplier) {
		Objects.requireNonNull(identity);
		Objects.requireNonNull(universe);
		Objects.requireNonNull(atomBuilderSupplier);
		return new RadixApplicationAPI(identity, universe, atomBuilderSupplier);
	}

	public RadixIdentity getMyIdentity() {
		return identity;
	}

	public RadixAddress getMyAddress() {
		return ledger.getAddressFromPublicKey(identity.getPublicKey());
	}

	public Observable<UnencryptedData> getReadableData(RadixAddress address) {
		Objects.requireNonNull(address);

		return ledger.getAllAtoms(address.getUID(), ApplicationPayloadAtom.class)
			.map(dataStoreTranslator::fromAtom)
			.flatMapMaybe(data -> identity.decrypt(data).toMaybe().onErrorComplete());
	}

	public Result storeData(Data data, RadixAddress address) {
		DataStore dataStore = new DataStore(data, address);

		AtomBuilder atomBuilder = atomBuilderSupplier.get();
		ConnectableObservable<AtomSubmissionUpdate> updates = dataStoreTranslator.translate(dataStore, atomBuilder)
			.andThen(Single.fromCallable(() -> atomBuilder.buildWithPOWFee(ledger.getMagic(), address.getPublicKey())))
			.flatMap(identity::sign)
			.flatMapObservable(ledger::submitAtom)
			.replay();

		updates.connect();

		return new Result(updates);
	}

	public Result storeData(Data data, RadixAddress address0, RadixAddress address1) {
		DataStore dataStore = new DataStore(data, address0, address1);

		AtomBuilder atomBuilder = atomBuilderSupplier.get();
		ConnectableObservable<AtomSubmissionUpdate> updates = dataStoreTranslator.translate(dataStore, atomBuilder)
			.andThen(Single.fromCallable(() -> atomBuilder.buildWithPOWFee(ledger.getMagic(), address0.getPublicKey())))
			.flatMap(identity::sign)
			.flatMapObservable(ledger::submitAtom)
			.replay();

		updates.connect();

		return new Result(updates);
	}

	public Observable<TokenTransfer> getMyTokenTransfers(Asset tokenClass) {
		return getTokenTransfers(getMyAddress(), tokenClass);
	}

	public Observable<TokenTransfer> getTokenTransfers(RadixAddress address, Asset tokenClass) {
		Objects.requireNonNull(address);
		Objects.requireNonNull(tokenClass);

		return Observable.combineLatest(
			Observable.fromCallable(() -> new TransactionAtoms(address, tokenClass.getId())),
			ledger.getAllAtoms(address.getUID(), TransactionAtom.class),
			(transactionAtoms, atom) ->
				transactionAtoms.accept(atom)
					.getNewValidTransactions()
		)
		.flatMap(atoms -> atoms.map(tokenTransferTranslator::fromAtom));
	}

	public Observable<Amount> getMyBalance(Asset tokenClass) {
		return getBalance(getMyAddress(), tokenClass);
	}

	public Observable<Amount> getBalance(RadixAddress address, Asset tokenClass) {
		Objects.requireNonNull(address);
		Objects.requireNonNull(tokenClass);

		return this.consumableDataSource.getConsumables(address)
			.map(Collection::stream)
			.map(stream -> stream
				.filter(consumable -> consumable.getAssetId().equals(tokenClass.getId()))
				.mapToLong(Consumable::getQuantity)
				.sum()
			)
			.map(balanceInSubUnits -> Amount.subUnitsOf(balanceInSubUnits, tokenClass))
			.share();
	}

	public Result sendTokens(RadixAddress to, Amount amount) {
		return transferTokens(getMyAddress(), to, amount);
	}

	public Result sendTokens(RadixAddress to, Amount amount, Data attachment) {
		return transferTokens(getMyAddress(), to, amount, attachment);
	}

	public Result transferTokens(RadixAddress from, RadixAddress to, Amount amount, Data attachment) {
		TokenTransfer tokenTransfer = TokenTransfer.create(from, to, amount.getTokenClass(), amount.getAmountInSubunits(), attachment);
		AtomBuilder atomBuilder = atomBuilderSupplier.get();

		ConnectableObservable<AtomSubmissionUpdate> updates = tokenTransferTranslator.translate(tokenTransfer, atomBuilder)
			.andThen(Single.fromCallable(() -> atomBuilder.buildWithPOWFee(ledger.getMagic(), from.getPublicKey())))
			.flatMap(identity::sign)
			.flatMapObservable(ledger::submitAtom)
			.replay();

		updates.connect();

		return new Result(updates);
	}

	public Result transferTokens(RadixAddress from, RadixAddress to, Amount amount) {
		TokenTransfer tokenTransfer = TokenTransfer.create(from, to, amount.getTokenClass(), amount.getAmountInSubunits());
		AtomBuilder atomBuilder = atomBuilderSupplier.get();

		ConnectableObservable<AtomSubmissionUpdate> updates = tokenTransferTranslator.translate(tokenTransfer, atomBuilder)
			.andThen(Single.fromCallable(() -> atomBuilder.buildWithPOWFee(ledger.getMagic(), from.getPublicKey())))
			.flatMap(identity::sign)
			.flatMapObservable(ledger::submitAtom)
			.replay();

		updates.connect();

		return new Result(updates);
	}
}
