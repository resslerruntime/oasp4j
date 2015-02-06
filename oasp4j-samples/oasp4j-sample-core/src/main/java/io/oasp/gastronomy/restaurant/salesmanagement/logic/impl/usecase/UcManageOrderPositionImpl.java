package io.oasp.gastronomy.restaurant.salesmanagement.logic.impl.usecase;

import io.oasp.gastronomy.restaurant.general.common.api.constants.PermissionConstants;
import io.oasp.gastronomy.restaurant.general.common.api.exception.IllegalEntityStateException;
import io.oasp.gastronomy.restaurant.general.common.api.exception.IllegalPropertyChangeException;
import io.oasp.gastronomy.restaurant.offermanagement.common.api.Offer;
import io.oasp.gastronomy.restaurant.offermanagement.logic.api.Offermanagement;
import io.oasp.gastronomy.restaurant.offermanagement.logic.api.to.OfferEto;
import io.oasp.gastronomy.restaurant.salesmanagement.common.api.OrderPosition;
import io.oasp.gastronomy.restaurant.salesmanagement.common.api.datatype.OrderPositionState;
import io.oasp.gastronomy.restaurant.salesmanagement.common.api.datatype.OrderState;
import io.oasp.gastronomy.restaurant.salesmanagement.common.api.datatype.ProductOrderState;
import io.oasp.gastronomy.restaurant.salesmanagement.dataaccess.api.OrderEntity;
import io.oasp.gastronomy.restaurant.salesmanagement.dataaccess.api.OrderPositionEntity;
import io.oasp.gastronomy.restaurant.salesmanagement.logic.api.Salesmanagement;
import io.oasp.gastronomy.restaurant.salesmanagement.logic.api.to.OrderEto;
import io.oasp.gastronomy.restaurant.salesmanagement.logic.api.to.OrderPositionEto;
import io.oasp.gastronomy.restaurant.salesmanagement.logic.api.usecase.UcManageOrderPosition;
import io.oasp.gastronomy.restaurant.salesmanagement.logic.base.usecase.AbstractOrderPositionUc;

import java.util.List;
import java.util.Objects;

import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.inject.Named;

import net.sf.mmm.util.exception.api.ObjectNotFoundUserException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of {@link UcManageOrderPosition}.
 *
 * @author jozitz
 */
@Named
public class UcManageOrderPositionImpl extends AbstractOrderPositionUc implements UcManageOrderPosition {

  /** Logger instance. */
  private static final Logger LOG = LoggerFactory.getLogger(UcManageOrderPositionImpl.class);

  private Salesmanagement salesManagement;

  private Offermanagement offerManagement;

  /**
   * The constructor.
   */
  public UcManageOrderPositionImpl() {

    super();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  @RolesAllowed(PermissionConstants.SAVE_ORDER_POSITION)
  public OrderPositionEto createOrderPosition(OfferEto offer, OrderEto order, String comment) {

    Objects.requireNonNull(offer, "offer");
    Long offerId = offer.getId();
    OfferEto offerFromDb = this.offerManagement.findOffer(offerId);
    if (offerFromDb == null) {
      throw new ObjectNotFoundUserException(Offer.class, offerId);
    }
    OrderPositionEntity orderPosition = new OrderPositionEntity();

    order.setState(OrderState.OPEN);
    // Declare the order position.
    orderPosition.setOfferId(offerId);
    orderPosition.setOfferName(offerFromDb.getDescription());
    orderPosition.setPrice(offerFromDb.getPrice());
    orderPosition.setOrder(getBeanMapper().map(this.salesManagement.saveOrder(order), OrderEntity.class));
    orderPosition.setComment(comment);

    // Save the order position and return it.
    getOrderPositionDao().save(orderPosition);

    LOG.debug("The order position with id '" + orderPosition.getId()
        + "' has been created. It's linked with order id '" + order.getId() + "' and offer id '" + offerId + "'.");

    return getBeanMapper().map(orderPosition, OrderPositionEto.class);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  @RolesAllowed(PermissionConstants.SAVE_ORDER_POSITION)
  public OrderPositionEto saveOrderPosition(OrderPositionEto orderPosition) {

    Objects.requireNonNull(orderPosition, "orderPosition");

    Long orderPositionId = orderPosition.getId();
    String action;
    if (orderPositionId == null) {
      action = "saved";
    } else {
      OrderPositionEntity targetOrderPosition = getOrderPositionDao().find(orderPositionId);
      verifyUpdate(targetOrderPosition, orderPosition);
      action = "updated";
    }

    OrderPositionEntity orderPositionEntity = getBeanMapper().map(orderPosition, OrderPositionEntity.class);
    orderPositionEntity = getOrderPositionDao().save(orderPositionEntity);
    LOG.debug("The order position with id {} has been {}.", orderPositionEntity.getId(), action);
    return getBeanMapper().map(orderPositionEntity, OrderPositionEto.class);
  }

  /**
   * @param currentOrderPosition is the current {@link OrderPosition} from the persistence.
   * @param updateOrderPosition is the new {@link OrderPosition} to update to.
   */
  private void verifyUpdate(OrderPosition currentOrderPosition, OrderPosition updateOrderPosition) {

    if (!Objects.equals(currentOrderPosition.getOrderId(), currentOrderPosition.getOrderId())) {
      throw new IllegalPropertyChangeException(updateOrderPosition, "orderId");
    }
    if (!Objects.equals(currentOrderPosition.getOfferId(), currentOrderPosition.getOfferId())) {
      throw new IllegalPropertyChangeException(updateOrderPosition, "offerId");
    }
    OrderPositionState currentState = currentOrderPosition.getState();
    OrderPositionState newState = updateOrderPosition.getState();
    ProductOrderState newDrinkState = updateOrderPosition.getDrinkState();

    verifyOrderPositionStateChange(updateOrderPosition, currentState, newState);

    // TODO add verification methods of other sub-states of OrderPosition (i.e. Meal and SideDish)
    verifyDrinkStateChange(updateOrderPosition, currentState, newState, newDrinkState);

  }

  /**
   * Verifies if an update of the {@link OrderPositionState} is legal.
   *
   * @param updateOrderPosition the new {@link OrderPosition} to update to.
   * @param currentState the old/current {@link OrderPositionState} of the {@link OrderPosition}.
   * @param newState new {@link OrderPositionState} of the {@link OrderPosition} to be updated to.
   */
  private void verifyOrderPositionStateChange(OrderPosition updateOrderPosition, OrderPositionState currentState,
      OrderPositionState newState) {

    switch (currentState) {
    case CANCELLED:
      if ((newState != OrderPositionState.CANCELLED) && (newState != OrderPositionState.ORDERED)) {
        throw new IllegalEntityStateException(updateOrderPosition, currentState, newState);
      }
      break;
    case ORDERED:
      if ((newState != OrderPositionState.ORDERED) && (newState != OrderPositionState.CANCELLED)
          && (newState != OrderPositionState.PREPARED)) {
        throw new IllegalEntityStateException(updateOrderPosition, currentState, newState);
      }
      break;
    case PREPARED:
      // from here we can go to any other state (back to ORDERED in case that the kitchen has to rework)
      break;
    case DELIVERED:
      if ((newState == OrderPositionState.PREPARED) || (newState == OrderPositionState.ORDERED)) {
        throw new IllegalEntityStateException(updateOrderPosition, currentState, newState);
      }
      break;
    case PAYED:
      if (newState != OrderPositionState.PAYED) {
        throw new IllegalEntityStateException(updateOrderPosition, currentState, newState);
      }
      break;
    default:
      LOG.error("Illegal state {}", currentState);
      break;
    }

  }

  /**
   * Verifies if an update of the {@link DrinkState} is legal. This verification is based on both the states of
   * {@link DrinkState} and {@link OrderPositionState}.
   *
   * @param updateOrderPosition the new {@link OrderPosition} to update to.
   * @param currentState the old/current {@link OrderPositionState} of the {@link OrderPosition}.
   * @param newState new {@link OrderPositionState} of the {@link OrderPosition} to be updated to.
   * @param newDrinkState new {@link ProductOrderState} of the drink of the {@link OrderPosition} to be updated to.
   */
  private void verifyDrinkStateChange(OrderPosition updateOrderPosition, OrderPositionState currentState,
      OrderPositionState newState, ProductOrderState newDrinkState) {

    switch (currentState) {
    case CANCELLED:
      if ((newState != OrderPositionState.CANCELLED) && (newState != OrderPositionState.ORDERED)) {
        throw new IllegalEntityStateException(updateOrderPosition, currentState, newDrinkState);
      }
      break;
    case ORDERED:
      if ((newState != OrderPositionState.ORDERED) && (newState != OrderPositionState.CANCELLED)
          && (newState != OrderPositionState.PREPARED) && (newDrinkState != ProductOrderState.ORDERED)
          && (newDrinkState != ProductOrderState.PREPARED)) {
        throw new IllegalEntityStateException(updateOrderPosition, currentState, newDrinkState);
      }
      break;
    case PREPARED:
      // from here we can go to any other state (back to ORDERED in case that the kitchen has to rework)
      break;
    case DELIVERED:
      if ((newState == OrderPositionState.PREPARED) || (newState == OrderPositionState.ORDERED)
          || (newDrinkState == ProductOrderState.PREPARED) || (newDrinkState == ProductOrderState.ORDERED)) {
        throw new IllegalEntityStateException(updateOrderPosition, currentState, newDrinkState);
      }
      break;
    case PAYED:
      if (newState != OrderPositionState.PAYED) {
        throw new IllegalEntityStateException(updateOrderPosition, currentState, newDrinkState);
      }
      break;
    default:
      LOG.error("Illegal state {}", currentState);
      break;
    }

  }

  /**
   * {@inheritDoc}
   */
  @Override
  @RolesAllowed(PermissionConstants.SAVE_ORDER_POSITION)
  public void markOrderPositionDrinkAs(OrderPositionEto orderPosition, OrderPositionState newState,
      ProductOrderState newDrinkState) {

    Objects.requireNonNull(orderPosition, "orderPosition");

    long orderPositionId = orderPosition.getId();
    OrderPositionEntity targetOrderPosition = getOrderPositionDao().findOne(orderPositionId);

    if (targetOrderPosition == null) {
      throw new ObjectNotFoundUserException(OrderPosition.class, orderPositionId);
    }

    OrderPositionState currentState = targetOrderPosition.getState();

    if ((newState == OrderPositionState.PREPARED) && (newDrinkState == ProductOrderState.ORDERED)
        && (currentState == OrderPositionState.ORDERED)

        || (newState == OrderPositionState.DELIVERED) && (newDrinkState == ProductOrderState.DELIVERED)
        && (currentState == OrderPositionState.PREPARED)

        || (newState == OrderPositionState.PAYED) && (newDrinkState == ProductOrderState.DELIVERED)
        && (currentState == OrderPositionState.DELIVERED)

        || (newState == OrderPositionState.CANCELLED) && (newDrinkState == ProductOrderState.DELIVERED)
        && (currentState != OrderPositionState.PAYED)) {
      targetOrderPosition.setState(newState);
      targetOrderPosition.setDrinkState(newDrinkState);
    } else {
      throw new IllegalEntityStateException(targetOrderPosition, currentState, newDrinkState);
    }

    // Marks related order as closed
    if (newState == OrderPositionState.CANCELLED || newState == OrderPositionState.PAYED) {
      List<OrderPositionEto> orderpositions =
          this.salesManagement.findOpenOrderPositionsByOrderId(orderPosition.getOrderId());
      if (orderpositions == null || orderpositions.isEmpty()) {
        targetOrderPosition.getOrder().setState(OrderState.CLOSED);
      }
    }
    getOrderPositionDao().save(targetOrderPosition);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  @RolesAllowed(PermissionConstants.SAVE_ORDER_POSITION)
  public void markOrderPositionAs(OrderPositionEto orderPosition, OrderPositionState newState) {

    Objects.requireNonNull(orderPosition, "orderPosition");

    long orderPositionId = orderPosition.getId();
    OrderPositionEntity targetOrderPosition = getOrderPositionDao().findOne(orderPositionId);

    if (targetOrderPosition == null) {
      throw new ObjectNotFoundUserException(OrderPosition.class, orderPositionId);
    }

    OrderPositionState currentState = targetOrderPosition.getState();

    if ((newState == OrderPositionState.PREPARED) && (currentState == OrderPositionState.ORDERED)

    || (newState == OrderPositionState.DELIVERED) && (currentState == OrderPositionState.PREPARED)

    || (newState == OrderPositionState.PAYED) && (currentState == OrderPositionState.DELIVERED)

    || (newState == OrderPositionState.CANCELLED) && (currentState != OrderPositionState.PAYED)) {
      targetOrderPosition.setState(newState);
    } else {
      throw new IllegalEntityStateException(targetOrderPosition, currentState, newState);
    }

    // Marks related order as closed
    if (newState == OrderPositionState.CANCELLED || newState == OrderPositionState.PAYED) {
      List<OrderPositionEto> orderpositions =
          this.salesManagement.findOpenOrderPositionsByOrderId(orderPosition.getOrderId());
      if (orderpositions == null || orderpositions.isEmpty()) {
        targetOrderPosition.getOrder().setState(OrderState.CLOSED);
      }
    }
    getOrderPositionDao().save(targetOrderPosition);
  }

  /**
   * @param salesManagement the {@link Salesmanagement} to {@link Inject}.
   */
  @Inject
  public void setSalesManagement(Salesmanagement salesManagement) {

    this.salesManagement = salesManagement;
  }

  /**
   * @param offerManagement the {@link Offermanagement} to {@link Inject}.
   */
  @Inject
  public void setOfferManagement(Offermanagement offerManagement) {

    this.offerManagement = offerManagement;
  }

}
