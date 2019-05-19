package keifer.service;

import keifer.api.model.Card;
import keifer.api.model.Deck;
import keifer.converter.CardConverter;
import keifer.converter.DeckConverter;
import keifer.persistence.CardRepository;
import keifer.persistence.DeckRepository;
import keifer.persistence.model.CardEntity;
import keifer.persistence.model.DeckEntity;
import keifer.persistence.model.DeckSnapshotEntity;
import keifer.service.model.CardCondition;
import lombok.NonNull;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class DeckServiceImpl implements DeckService {

    private DeckRepository deckRepository;
    private DeckConverter deckConverter;
    private CardRepository cardRepository;
    private CardConverter cardConverter;
    private TcgService tcgService;

    public DeckServiceImpl(@NonNull DeckRepository deckRepository,
                           @NonNull DeckConverter deckConverter,
                           @NonNull CardRepository cardRepository,
                           @NonNull CardConverter cardConverter,
                           @NonNull TcgService tcgService) {
        this.deckRepository = deckRepository;
        this.deckConverter = deckConverter;
        this.cardRepository = cardRepository;
        this.cardConverter = cardConverter;
        this.tcgService = tcgService;
    }

    @Override
    public List<Deck> getDecks() {

        List<Deck> decks = new ArrayList<>();
        decks.add(getDeckOverview());
        decks.addAll(deckRepository.findAll().stream().map(deckConverter::convert).collect(Collectors.toList()));

        return decks;
    }

    @Override
    public Deck getDeck(Long deckId) {

        // Deck Overview
        if (deckId == 0) {
            return getDeckOverview();
        }

        DeckEntity deckEntity = fetchDeck(deckId);

        return deckConverter.convert(deckEntity);
    }

    private Deck getDeckOverview() {

        Deck deck = Deck.builder().id(0L).name("Deck Overview").cards(new ArrayList<>()).build();

        List<Deck> decks = deckRepository.findAll().stream().map(deckConverter::convert).collect(Collectors.toList());

        long count = 0L;
        for (Deck newDeck : decks) {
            double deckValue = 0;
            for (Card card : newDeck.getCards()) {
                deckValue += (card.getMarketPrice() * card.getQuantity());
            }

            deck.getCards().add(Card.builder()
                    .id(count++)
                    .name(newDeck.getName())
                    .version("")
                    .purchasePrice(0.0)
                    .quantity(1)
                    .marketPrice(deckValue)
                    .build());
        }

        return deck;
    }

    @Override
    public void saveCard(Long deckId, Card card) {

        DeckEntity deckEntity = fetchDeck(deckId);

        String productConditionId = tcgService.fetchProductConditionId(card);
        double marketPrice = tcgService.fetchMarketPrice(productConditionId);

        CardEntity cardEntity = CardEntity.builder()
                .name(card.getName())
                .version(card.getVersion())
                .isFoil(card.getIsFoil())
                .cardCondition(CardCondition.valueOf(card.getCardCondition()))
                .purchasePrice(card.getPurchasePrice())
                .quantity(card.getQuantity())
                .productConditionId(productConditionId)
                .marketPrice(marketPrice)
                .deckEntity(deckEntity)
                .build();

        // Save new card
        if (card.getId() == null) {
            deckEntity.getCardEntities().add(cardEntity);
            deckRepository.save(deckEntity);
        }
        // Update old card
        else {
            cardEntity.setId(card.getId());
            cardRepository.save(cardEntity);
        }
    }

    @Override
    public void refreshDeck(Long deckId) {

        // Deck Overview
        if (deckId == 0) {
            List<DeckEntity> deckEntities = deckRepository.findAll();
            for (DeckEntity deckEntity : deckEntities) {
                double aggregateValue = 0;
                for (CardEntity cardEntity : deckEntity.getCardEntities()) {
                    aggregateValue += saveCardEntity(cardEntity);
                }

                saveDeckEntitySnapshot(deckEntity, aggregateValue);
            }

            return;
        }

        DeckEntity deckEntity = fetchDeck(deckId);
        double aggregateValue = 0;

        for (CardEntity cardEntity : deckEntity.getCardEntities()) {
            aggregateValue += saveCardEntity(cardEntity);
        }

        saveDeckEntitySnapshot(deckEntity, aggregateValue);
    }

    private DeckEntity fetchDeck(Long deckId) {

        DeckEntity deckEntity = deckRepository.findOneById(deckId);
        if (deckEntity == null) {
            throw new Error("Deck with id: " + deckId + " not found.");
        }

        return deckEntity;
    }

    private double saveCardEntity(CardEntity cardEntity) {

        cardEntity.setMarketPrice(tcgService.fetchMarketPrice(cardEntity.getProductConditionId()));
        cardRepository.save(cardEntity);

        return cardEntity.getMarketPrice();
    }

    private void saveDeckEntitySnapshot(DeckEntity deckEntity, double aggregateValue) {

        deckEntity.getDeckSnapshotEntities().add(DeckSnapshotEntity.builder()
                .value(aggregateValue)
                .timestamp(LocalDateTime.now())
                .deckEntity(deckEntity)
                .build());

        deckRepository.save(deckEntity);
    }
}
