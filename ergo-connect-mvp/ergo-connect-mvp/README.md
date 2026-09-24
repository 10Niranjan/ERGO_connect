# ERGO Connect MVP Starter

This is a runnable frontend MVP for ERGO Connect based on the requirements discussed.

## What is included
- Login screen with demo users
- Role-based navigation
- ERGO/Admin/Management dashboard with RFQ, NPD, Quality, OFP summary
- RFQ creation screen
- RFQ list with quote details in one row
- Remarks/notes expandable below the RFQ row
- NPD conversion queue
- OFP, Quality, Documents, Communication, Admin module shells
- No CDN icon dependency
- No lucide-react dependency
- No shadcn dependency

## Run locally

```bash
npm install
npm run dev
```

Open the URL shown by Vite, usually:

```text
http://localhost:5173
```

## Demo Login Details

Password for all users:

```text
password123
```

### ERGO
- admin@ergo.local
- management@ergo.local
- kam@ergo.local
- rfq@ergo.local
- npd@ergo.local
- ofp@ergo.local
- quality@ergo.local

### Customer
- buyer@cresco.local
- customer.npd@cresco.local
- customer.ofp@cresco.local
- customer.quality@cresco.local
- customer.manager@cresco.local

### Supplier
- sales@metalform.local
- supplier.npd@metalform.local
- supplier.ofp@metalform.local
- supplier.quality@metalform.local
- supplier.manager@metalform.local

## Important Notes
This is an MVP frontend starter, not yet a production backend-connected system.
The next production step is to add:
- Backend API
- Database
- File upload storage
- Authentication/token system
- Audit logging
- Workflow engine
