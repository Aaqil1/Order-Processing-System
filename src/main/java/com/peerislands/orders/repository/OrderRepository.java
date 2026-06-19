package com.peerislands.orders.repository;

import com.peerislands.orders.domain.Order;
import com.peerislands.orders.domain.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    Page<Order> findByStatus(OrderStatus status, Pageable pageable);

    @Query("SELECT o FROM Order o LEFT JOIN FETCH o.items WHERE o.id = :id")
    Optional<Order> findByIdWithItems(@Param("id") UUID id);

    // Cancels only if still PENDING. Returns 0 when it's no longer cancellable,
    // which the service maps to 409. The status predicate makes this safe against
    // the promotion job without locking; the version bump is the optimistic guard.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Order o SET o.status = com.peerislands.orders.domain.OrderStatus.CANCELLED, " +
           "o.version = o.version + 1 " +
           "WHERE o.id = :id AND o.status = com.peerislands.orders.domain.OrderStatus.PENDING")
    int cancelIfPending(@Param("id") UUID id);

    // Promote every PENDING order to PROCESSING in a single statement.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Order o SET o.status = com.peerislands.orders.domain.OrderStatus.PROCESSING, " +
           "o.version = o.version + 1 " +
           "WHERE o.status = com.peerislands.orders.domain.OrderStatus.PENDING")
    int promotePending();
}
