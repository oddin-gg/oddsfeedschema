BUILD_DIR := build

.PHONY: check clean

# Compile every schema and validate every captured payload against it.
check: $(BUILD_DIR)/SchemaCheck.class
	@java -cp $(BUILD_DIR) SchemaCheck .

$(BUILD_DIR)/SchemaCheck.class: tools/SchemaCheck.java
	@mkdir -p $(BUILD_DIR)
	@javac -d $(BUILD_DIR) tools/SchemaCheck.java

clean:
	@rm -rf $(BUILD_DIR)
