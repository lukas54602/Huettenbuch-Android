# Android Hardware Bridge v5 - Go-Live

Laravel is the only source of truth for productive hardware configuration and business logic. Android discovers hardware, executes explicit commands, reports status/events, and shows a read-only diagnostic snapshot of the last configuration supplied by Laravel.

Core JS interface: getHardware, setHardwareConfig (read-only diagnostic snapshot supplied by Laravel), rescanHardware, readNfc/stopNfc, showCustomerDisplay/clearCustomerDisplay, print/getPrinterStatus/testPrinter, testDisplay, sumUpLogin/openSumUpReaderSettings/getSumUpReader/startPayment/getPaymentStatus/resetPaymentState, getStatus/getLogs/clearLogs.

The Android admin screen does not select productive hardware. Its Hardware view compares configured IDs from Laravel with currently discovered devices.
