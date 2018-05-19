package com.its.orderservice;

import java.util.Date;
import java.util.Optional;
import java.util.UUID;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.statemachine.StateContext;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.access.StateMachineAccess;
import org.springframework.statemachine.access.StateMachineFunction;
import org.springframework.statemachine.action.Action;
import org.springframework.statemachine.config.EnableStateMachineFactory;
import org.springframework.statemachine.config.StateMachineConfigurerAdapter;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.statemachine.config.builders.StateMachineConfigurationConfigurer;
import org.springframework.statemachine.config.builders.StateMachineStateConfigurer;
import org.springframework.statemachine.config.builders.StateMachineTransitionConfigurer;
import org.springframework.statemachine.listener.StateMachineListenerAdapter;
import org.springframework.statemachine.state.State;
import org.springframework.statemachine.support.DefaultStateMachineContext;
import org.springframework.statemachine.support.StateMachineInterceptorAdapter;
import org.springframework.statemachine.transition.Transition;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@SpringBootApplication
public class OrderServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(OrderServiceApplication.class, args);
	}
}

enum OrderEvents {
	FULFILL,
	PAY,
	CANCEL
}

enum OrderStates {
	SUBMITTED,
	PAID,
	FULFILLED,
	CANCELLED
}

@Slf4j
@Component
class Runner implements ApplicationRunner {

	private StateMachineFactory <OrderStates, OrderEvents> factory;
	private final OrderService orderService;
	
	/*public Runner(StateMachineFactory<OrderStates, OrderEvents> factory, OrderService orderService) {
		this.factory = factory;
		this.orderService = orderService;
	}*/

	public Runner( OrderService orderService) {
		this.orderService = orderService;
	}
	
	@Override
	public void run(ApplicationArguments args) throws Exception {
		/*Long orderId = 13232L;
		StateMachine <OrderStates, OrderEvents> machine = this.factory.getStateMachine(Long.toString(orderId));
		machine.getExtendedState().getVariables().putIfAbsent("orderId", orderId);
		machine.start();
		machine.getState().getId().name();
		log.info("Current state : " + machine.getState().getId().name());
		machine.sendEvent(OrderEvents.PAY);
		log.info("Current state : " + machine.getState().getId().name());
		Message <OrderEvents> eventsMessage = MessageBuilder
												.withPayload(OrderEvents.FULFILL)
												.setHeader("a", "b")
												.build();
		machine.sendEvent(eventsMessage);
		log.info("Current state : " + machine.getState().getId().name());*/
		
		Order order = this.orderService.createOrder(new Date());
		log.info("After calling createOrder : " + order.getId());
		StateMachine <OrderStates, OrderEvents> paymentStateMachine = this.orderService.pay(order.getId(), UUID.randomUUID().toString());
		log.info("After calling pay : " + paymentStateMachine.getState().getId().name());
		log.info("order id {}" + orderService.byId(order.getId()));
		
		StateMachine <OrderStates, OrderEvents> fulfilled = this.orderService.fulfill(order.getId());
		log.info("After calling fulfilled : " + fulfilled.getState().getId().name());
		log.info("order id {}" + orderService.byId(order.getId()));
	}
}

interface OrderRepository extends JpaRepository<Order, Long>{
	
}

@Entity (name="ORDERS")
@Data
@NoArgsConstructor
@AllArgsConstructor
class Order {
	@Id
	@GeneratedValue
	private Long id;
	private Date dateTime;
	private String state;
	
	public Order(Date dateTime, OrderStates os) {
		this.dateTime = dateTime;
		this.setOrderState(os);
	}
	
	public OrderStates getOrderState() {
		return OrderStates.valueOf(this.state);
	}
	
	public void setOrderState(OrderStates os) {
		this.state = os.name();
	}
}

@Slf4j
@Service
class OrderService {
	private final OrderRepository orderRepository;
	private final StateMachineFactory<OrderStates, OrderEvents> factory;

	public OrderService(OrderRepository orderRepository, StateMachineFactory<OrderStates, OrderEvents> factory) {
		this.orderRepository = orderRepository;
		this.factory = factory;
	}
	
	Order byId(Long id) {
		Order foundOrder = null;
		Optional<Order> order = this.orderRepository.findById(id);
		if (order.isPresent()) {
			foundOrder = order.get();
		}
		return foundOrder;
	}
	public Order createOrder(Date when) {
		return this.orderRepository.save(new Order(when, OrderStates.SUBMITTED));
	}
	
	public  StateMachine<OrderStates, OrderEvents> fulfill(Long orderId) {
		StateMachine <OrderStates, OrderEvents> sm = this.build(orderId);
		Message<OrderEvents> fulfillmentMesssage = MessageBuilder.withPayload(OrderEvents.FULFILL)
													.setHeader(ORDER_ID_HEADER, orderId)
													.build();
		
		sm.sendEvent(fulfillmentMesssage);
		
		return sm;
	}
	
	public  StateMachine<OrderStates, OrderEvents> pay(Long orderId, String paymentConfirmationNumber) {
		StateMachine <OrderStates, OrderEvents> sm = this.build(orderId);
		
		Message<OrderEvents> paymentMesssage = MessageBuilder.withPayload(OrderEvents.PAY)
													.setHeader(ORDER_ID_HEADER, orderId)
													.setHeader("paymentConfirmationNumber", paymentConfirmationNumber)
													.build();
		sm.sendEvent(paymentMesssage);
		
		return sm;
	}

	private static final String ORDER_ID_HEADER = "orderId";
	
	private StateMachine<OrderStates, OrderEvents> build(Long orderId) {
		//this.orderRepository.find
		
		Optional<Order> order = this.orderRepository.findById(orderId);
		if (order.isPresent()) {
			Order actualOrder = order.get();
			String  orderIdKey = Long.toString(actualOrder.getId());
			
			StateMachine <OrderStates, OrderEvents> sm = this.factory.getStateMachine(orderIdKey);
			sm.stop();
			
			sm.getStateMachineAccessor()
				.doWithAllRegions(new StateMachineFunction<StateMachineAccess<OrderStates,OrderEvents>>() {
				
				@Override
				public void apply(StateMachineAccess<OrderStates, OrderEvents> sma) {
					sma.addStateMachineInterceptor(new StateMachineInterceptorAdapter<OrderStates, OrderEvents>(){

						@Override
						public void preStateChange(State<OrderStates, OrderEvents> state, Message<OrderEvents> message,
								Transition<OrderStates, OrderEvents> transition,
								StateMachine<OrderStates, OrderEvents> stateMachine) {
							Optional.ofNullable(message).ifPresent(msg -> {
								
								Optional.ofNullable(Long.class.cast(msg.getHeaders().getOrDefault(ORDER_ID_HEADER, -1L)))
									.ifPresent(orderId -> {
										Optional<Order> anOrder = orderRepository.findById(orderId);
										if (anOrder.isPresent()) {
											Order o = anOrder.get();
											o.setOrderState(state.getId());
											orderRepository.save(o);
										}
									});
								;
							});
						}
						
					});
					
					sma.resetStateMachine(new DefaultStateMachineContext<OrderStates, OrderEvents>(
							actualOrder.getOrderState(), null, null, null));
					
				}
			});
			
			sm.start();
			return sm;
		} else {
			log.info("Order not present . . .hence returning null");
			return null;
		}
		
		
	}
}

@Slf4j
@Configuration
@EnableStateMachineFactory
class SimpleEnumStateMachineConfiguration extends StateMachineConfigurerAdapter<OrderStates, OrderEvents> {
	
	
	@Override
	public void configure(StateMachineTransitionConfigurer<OrderStates, OrderEvents> transitions) throws Exception {
		/*transitions
			.withLocal()
			.source(OrderStates.SUBMITTED)
			.target(OrderStates.FULFILLED);*/
		
		transitions
			.withExternal().source(OrderStates.SUBMITTED).target(OrderStates.PAID).event(OrderEvents.PAY)
			.and()
			.withExternal().source(OrderStates.PAID).target(OrderStates.FULFILLED).event(OrderEvents.FULFILL)
			.and()
			.withExternal().source(OrderStates.SUBMITTED).target(OrderStates.CANCELLED).event(OrderEvents.CANCEL)
			.and()
			.withExternal().source(OrderStates.PAID).target(OrderStates.CANCELLED).event(OrderEvents.CANCEL)
			.and()
			.withExternal().source(OrderStates.FULFILLED).target(OrderStates.CANCELLED).event(OrderEvents.CANCEL);
	}


	@Override
	public void configure(StateMachineStateConfigurer<OrderStates, OrderEvents> states) throws Exception {
		states
			.withStates()
			.initial(OrderStates.SUBMITTED)
			.stateEntry(OrderStates.SUBMITTED, new Action<OrderStates, OrderEvents>() {

				@Override
				public void execute(StateContext<OrderStates, OrderEvents> context) {
					Long orderId = Long.class.cast(context.getExtendedState().getVariables().getOrDefault("orderId", 1L));
					log.info("order id is {}", orderId);
					log.info("entering submitted state");
					
				}
				
			})
			.state(OrderStates.PAID)
			.end(OrderStates.FULFILLED)
			.end(OrderStates.CANCELLED);
	}
	

	@Override
	public void configure(StateMachineConfigurationConfigurer<OrderStates, OrderEvents> config) throws Exception {
		StateMachineListenerAdapter<OrderStates, OrderEvents> adapter = new StateMachineListenerAdapter<OrderStates, OrderEvents>() {
			@Override
			public void stateChanged(State<OrderStates, OrderEvents> from, State<OrderStates, OrderEvents> to) {
				log.info(String.format("State changed (from : %s, to %s)", from + "", to +"" ));
			}
		};
		config.withConfiguration()
			.autoStartup(false)
			.listener(adapter);
	}
}
