import React from 'react';
import ReactDOM from 'react-dom/client';
import {HashRouter} from 'react-router-dom';
import {QueryClient, QueryClientProvider} from '@tanstack/react-query';
import App from './App';
import './styles.css';

const client = new QueryClient({defaultOptions: {queries: {staleTime: 10_000, gcTime: 600_000, retry: false, refetchOnWindowFocus: true}, mutations: {retry: false}}});
ReactDOM.createRoot(document.getElementById('root')!).render(<React.StrictMode><QueryClientProvider client={client}><HashRouter><App/></HashRouter></QueryClientProvider></React.StrictMode>);
