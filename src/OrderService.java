public class OrderService {

    public double calculateTotal(
            double price,
            int quantity) {

        return price * quantity;
    }

    public boolean isValidOrder(
            double total) {

        return total > 0;
    }
}