package org.banka1.exchangeservice.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import lombok.extern.slf4j.Slf4j;
import org.banka1.exchangeservice.domains.dtos.option.BetDto;
import org.banka1.exchangeservice.domains.dtos.option.OptionDto;
import org.banka1.exchangeservice.domains.dtos.order.OrderFilterRequest;
import org.banka1.exchangeservice.domains.dtos.order.OrderRequest;
import org.banka1.exchangeservice.domains.dtos.user.Position;
import org.banka1.exchangeservice.domains.dtos.user.UserDto;
import org.banka1.exchangeservice.domains.dtos.user.UserListingCreateDto;
import org.banka1.exchangeservice.domains.dtos.user.UserListingDto;
import org.banka1.exchangeservice.domains.entities.*;
import org.banka1.exchangeservice.domains.exceptions.BadRequestException;
import org.banka1.exchangeservice.domains.exceptions.NotFoundExceptions;
import org.banka1.exchangeservice.domains.mappers.OrderMapper;
import org.banka1.exchangeservice.repositories.ForexRepository;
import org.banka1.exchangeservice.repositories.OptionBetRepository;
import org.banka1.exchangeservice.repositories.OptionRepository;
import org.banka1.exchangeservice.repositories.OrderRepository;
import org.banka1.exchangeservice.repositories.StockRepository;
import org.banka1.exchangeservice.utils.JwtUtil;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Stream;

@Service
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;
    private final ForexRepository forexRepository;
    private final StockRepository stockRepository;
    private final OptionBetRepository optionBetRepository;
    private final OptionRepository optionRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ForexService forexService;
    private final StockService stockService;

    private final JwtUtil jwtUtil;

    @Value("${user.service.endpoint}")
    private String userServiceUrl;

    @Value("${jwt.secret}")
    private String SECRET_KEY;


    public OrderService(OrderRepository orderRepository, ForexRepository forexRepository,
                        StockRepository stockRepository, OptionBetRepository optionBetRepository, OptionRepository optionRepository, ForexService forexService,
                        StockService stockService, JwtUtil jwtUtil) {

        this.orderRepository = orderRepository;
        this.forexRepository = forexRepository;
        this.stockRepository = stockRepository;
        this.optionBetRepository = optionBetRepository;
        this.optionRepository = optionRepository;
        this.forexService = forexService;
        this.stockService = stockService;
        this.jwtUtil = jwtUtil;
    }

    public Order makeOrder(OrderRequest orderRequest, String token) {
        UserDto userDto = getUserDtoFromUserService(token);
        Double expectedPrice = calculateThePrice(orderRequest.getListingType(),orderRequest.getSymbol(),orderRequest.getQuantity());

        Order order = new Order();
        order.setEmail(userDto.getEmail());
        order.setUserId(userDto.getId());
        order.setExpectedPrice(expectedPrice);
        order.setRemainingQuantity(orderRequest.getQuantity());
        order.setLastModified(new Date());
        OrderMapper.INSTANCE.updateOrderFromOrderRequest(order, orderRequest);

        if(userDto.getBankAccount().getAccountBalance() < expectedPrice) {
            order.setOrderStatus(OrderStatus.REJECTED);
        }
        else if(userDto.getBankAccount().getDailyLimit() - expectedPrice < 0) {
            order.setOrderStatus(OrderStatus.ON_HOLD);
        }
        else {
            order.setOrderStatus(OrderStatus.APPROVED);
        }

        UserListingDto userListingDto = getUserListing(order.getUserId(), order.getListingType(), order.getListingSymbol(), token);
        if ((userListingDto == null || userListingDto.getQuantity() < order.getQuantity())
                && order.getOrderAction() == OrderAction.SELL) {
            order.setOrderStatus(OrderStatus.REJECTED);
        }

        if(order.getOrderStatus() != OrderStatus.REJECTED) {
            reduceDailyLimitForUser(token, userDto.getId(), expectedPrice);
        }

        orderRepository.save(order);
        if(order.getOrderStatus().equals(OrderStatus.APPROVED))
            mockExecutionOfOrder(order, token);

        return order;
    }

    public Double calculateThePrice(ListingType listingType, String symbol, Integer quantity){
        if(listingType.equals(ListingType.FOREX)){
         Forex forex = forexRepository.findBySymbol(symbol);
         return forex.getExchangeRate() * quantity;
        } else if (listingType.equals(ListingType.STOCK)) {
            Stock stock = stockRepository.findBySymbol(symbol);
            return stock.getPrice() * quantity;
        }
        return 0.0;
    }

    public UserDto getUserDtoFromUserService(String token){
        String url = userServiceUrl + "/users/my-profile";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", token)
                .method("GET", HttpRequest.BodyPublishers.noBody())
                .build();

        UserDto userDto = null;
        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            userDto = objectMapper.readValue(response.body(), UserDto.class);
        }catch (Exception e){
            e.printStackTrace();
        }

        return userDto;
    }

    public void reduceDailyLimitForUser(String token,Long userId, Double decreaseLimit){
        String url = userServiceUrl + "/users/reduce-daily-limit?userId=" + userId + "&decreaseLimit=" + decreaseLimit;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", token)
                .method("PUT", HttpRequest.BodyPublishers.noBody())
                .build();

        try {
            HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    public void mockExecutionOfOrder(Order order, String token) {
        UserListingDto userListingDto = getUserListing(order.getUserId(), order.getListingType(), order.getListingSymbol(), token);
        if(userListingDto == null) {
            UserListingCreateDto userListingCreateDto = new UserListingCreateDto();
            userListingCreateDto.setSymbol(order.getListingSymbol());
            userListingCreateDto.setQuantity(0);
            userListingCreateDto.setListingType(order.getListingType());

            try {
                String body = objectMapper.writeValueAsString(userListingCreateDto);
                String url = userServiceUrl + "/user-listings/create?userId=" + order.getUserId();
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", token)
                        .header("Content-Type", "application/json")
                        .method("POST", HttpRequest.BodyPublishers.ofString(body))
                        .build();

                HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
                String jsonUserListing = response.body();
                userListingDto = objectMapper.readValue(jsonUserListing, UserListingDto.class);

            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        Long listingId = userListingDto.getId();
        Random random = new Random();
        int quantity = random.nextInt(order.getRemainingQuantity() + 1);
        if (order.isAllOrNone() && quantity != order.getQuantity())
            return;

        double askPrice = 0;
        double bidPrice = 0;
        if (order.getListingType() == ListingType.FOREX) {
            Forex forex = forexRepository.findBySymbol(order.getListingSymbol());
            askPrice = forex.getAskPrice();
            bidPrice = forex.getBidPrice();
        } else if (order.getListingType() == ListingType.STOCK) {
            Stock stock = stockRepository.findBySymbol(order.getListingSymbol());
            askPrice = stock.getPrice();
            bidPrice = stock.getPrice();
        }

        switch (order.getOrderType()) {
            case STOP_ORDER -> {
                if ((order.getOrderAction() == OrderAction.BUY && order.getStopValue() < askPrice)
                        || (order.getOrderAction() == OrderAction.SELL && order.getStopValue() > bidPrice)) {
                    order.setOrderType(OrderType.MARKET_ORDER);
                }
                else {
                    return;
                }
            }
            case LIMIT_ORDER -> {
                if ((order.getOrderAction() == OrderAction.BUY && askPrice > order.getLimitValue())
                        || (order.getOrderAction() == OrderAction.SELL && bidPrice < order.getLimitValue())) {
                    return;
                }
            }
            case STOP_LIMIT_ORDER -> {
                if ((order.getOrderAction() == OrderAction.BUY && order.getStopValue() > askPrice)
                        || (order.getOrderAction() == OrderAction.SELL && order.getStopValue() < bidPrice)
                        || ((order.getOrderAction() == OrderAction.BUY && askPrice > order.getLimitValue())
                        || (order.getOrderAction() == OrderAction.SELL && bidPrice < order.getLimitValue()))) {
                    return;
                }
            }
        }

        int newQuantity;
        if(order.getOrderAction() == OrderAction.BUY) {
            newQuantity = quantity + userListingDto.getQuantity();
        } else {
            newQuantity = userListingDto.getQuantity() - quantity;
        }

        order.setRemainingQuantity(order.getRemainingQuantity() - quantity);
        if(order.getRemainingQuantity() == 0)
            order.setDone(true);

        Double accountBalanceToUpdate = calculateThePrice(order.getListingType(), order.getListingSymbol(), quantity);
        String urlBankAccount;
        if(order.getOrderAction() == OrderAction.BUY)
            urlBankAccount = userServiceUrl + "/users/decrease-balance?decreaseAccount=" + accountBalanceToUpdate;
        else
            urlBankAccount = userServiceUrl + "/users/increase-balance?increaseAccount=" + accountBalanceToUpdate;

        updateBankAccountBalance(token, urlBankAccount);

        String url = userServiceUrl + "/user-listings/update/" + listingId + "?newQuantity=" + newQuantity;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", token)
                .header("Content-Type", "application/json")
                .method("PUT", HttpRequest.BodyPublishers.ofString("")) // mozda treba mozda ne treba
                .build();
        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            userListingDto = objectMapper.readValue(response.body(), UserListingDto.class);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }

        order.setLastModified(new Date());
        orderRepository.save(order);
    }

    @RabbitListener(queues = "${rabbitmq.queue.forex.name}")
    public void receiveForex(Forex forex) {
        receive(ListingType.FOREX, forex.getSymbol());
    }

    @RabbitListener(queues = "${rabbitmq.queue.stock.name}")
    public void receiveStock(Stock stock) {
        receive(ListingType.STOCK, stock.getSymbol());
    }

    private void receive(ListingType listingType, String symbol){
        List<Order> notDoneOrders = orderRepository
                .findAllByListingTypeAndListingSymbolAndDone(listingType, symbol, false);
        String token = "Bearer " + jwtUtil.generateToken();

        notDoneOrders.forEach(order -> mockExecutionOfOrder(order, token));
    }

    public void updateBankAccountBalance(String token, String url){
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", token)
                .header("Content-Type", "application/json")
                .method("PUT", HttpRequest.BodyPublishers.ofString(""))
                .build();
        try {
            HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public void rejectOrder(String token, Long orderId) {
        UserDto userDto = getUserDtoFromUserService(token);
        if(userDto.getPosition() == Position.ADMINISTRATOR){
            Order order = orderRepository.findById(orderId).orElseThrow(() -> new NotFoundExceptions("order not found"));
            order.setOrderStatus(OrderStatus.REJECTED);
            orderRepository.save(order);
        }
    }

    public void approveOrder(String token, Long orderId) {
        UserDto userDto = getUserDtoFromUserService(token);
        if(userDto.getPosition() == Position.ADMINISTRATOR){
            Order order = orderRepository.findById(orderId).orElseThrow(() -> new NotFoundExceptions("order not found"));
            order.setOrderStatus(OrderStatus.APPROVED);
            orderRepository.save(order);
            mockExecutionOfOrder(order, token);
        }
    }

    public List<Order> getAllOrders(OrderFilterRequest orderFilterRequest) {
        Iterable<Order> orderIterable = orderRepository.findAll(orderFilterRequest.getPredicate());
        List<Order> orders = new ArrayList<>();
        orderIterable.forEach(orders::add);
        orders.forEach(order -> order.setExpectedPrice(calculateThePrice(order.getListingType(), order.getListingSymbol(), order.getQuantity())));

        return orders;
    }

    public List<Order> getOrdersByUser(OrderFilterRequest orderFilterRequest) {
        Iterable<Order> orderIterable = orderRepository.findAll(orderFilterRequest.getPredicate());
        List<Order> orders = new ArrayList<>();
        orderIterable.forEach(orders::add);
        orders.forEach(order -> order.setExpectedPrice(calculateThePrice(order.getListingType(), order.getListingSymbol(), order.getQuantity())));

        return orders;
    }

    public UserListingDto getUserListing(Long userId, ListingType listingType, String symbol, String token) {
        String url = userServiceUrl + "/user-listings?userId=" + userId;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", token)
                .method("GET", HttpRequest.BodyPublishers.noBody())
                .build();

        UserListingDto userListingDto = null;
        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            UserListingDto[] userListings = objectMapper.readValue(response.body(), UserListingDto[].class);

            userListingDto = Stream.of(userListings)
                    .filter(ul -> ul.getListingType() == listingType && ul.getSymbol().equals(symbol))
                    .findFirst()
                    .orElse(null);
        }catch (Exception e){
            e.printStackTrace();
        }

        return userListingDto;
    }

    public void placeBet(String token, Long optionId, BetDto bet) {
        UserDto userDto = getUserDtoFromUserService(token);
        Option option = optionRepository.findById(optionId).orElseThrow(() -> new NotFoundExceptions("Not found option!"));
        if (bet.getDate().isBefore(LocalDate.now()) || option.getExpirationDate().isBefore(bet.getDate())) {
            throw new BadRequestException("Invalid date");
        }

        String type;
        if (option.getOptionType().equals(OptionType.CALL)) {
            type = "C";
        } else type = "P";
        int year  = bet.getDate().getYear() % 100;

        int dd = bet.getDate().getDayOfMonth();
        String day;
        if (dd < 10) day = "0" + dd;
        else day = String.valueOf(dd);

        int mm = bet.getDate().getMonthValue();
        String month;
        if (mm < 10) month = "0" + mm;
        else month = String.valueOf(mm);

        String code = year + month + day + type + option.getStrike();
        OptionBet optionBet = new OptionBet(null, userDto.getId(), code , bet.getDate(), bet.getBet(), optionId, userDto.getEmail());
        optionBetRepository.save(optionBet);
    }


    public void rejectBet(String token, Long optionBetId) {
        UserDto userDto = getUserDtoFromUserService(token);
        OptionBet optionBet = optionBetRepository.findById(optionBetId).orElseThrow(() -> new NotFoundExceptions("Bet not found!"));
        if (!userDto.getId().equals(optionBet.getUserId())) {
            throw new BadRequestException("Bad request");
        }
        optionBetRepository.delete(optionBet);
    }

    public List<OptionBet> getMyBets(String token) {
        UserDto userDto = getUserDtoFromUserService(token);
        List<OptionBet> optionBets = optionBetRepository.findAllByUserId(userDto.getId());
        List<OptionBet> result = new ArrayList<>();
        optionBets.forEach(optionBet -> {
            if (optionBet.getDate().isAfter(LocalDate.now())) {
                result.add(optionBet);
            }
        });
        return result;
    }

    public List<Option> getAllOptions() {
       return optionRepository.findAll();
    }

    @Scheduled(cron = "0 0 8 * * *")
    private void checkBets() {
        List<OptionBet> optionBets = optionBetRepository.findAll().stream().filter(optionBet -> optionBet.getDate().equals(LocalDate.now())).toList();
        optionBets.forEach(optionBet -> {
            Option option = optionRepository.findById(optionBet.getOptionId()).orElseThrow(() -> new NotFoundExceptions("Option not found"));
            String token = generateToken(optionBet.getUserId(), optionBet.getEmail());
            if (option.getOptionType().equals(OptionType.CALL)) {
                String urlBankAccount = userServiceUrl + "/users/decrease-balance?decreaseAccount=" + option.getPrice();
                updateBankAccountBalance(token, urlBankAccount);
                // skini pare sa racuna
            } else {
                String urlBankAccount = userServiceUrl + "/users/increase-balance?increaseAccount=" + option.getPrice();
                updateBankAccountBalance(token, urlBankAccount);
                //uplati pare
            }
        });
    }

    private String generateToken(Long userId, String email){

        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("roles", "ROLE_ADMIN");

        return Jwts.builder()
                .setClaims(claims)
                .setSubject(email)
                .setIssuedAt(new Date(System.currentTimeMillis()))
                .setExpiration(new Date(System.currentTimeMillis() + 1000 * 60 * 60 * 10))
                .signWith(SignatureAlgorithm.HS512, SECRET_KEY).compact();
    }
}
